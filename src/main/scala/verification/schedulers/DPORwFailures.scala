package akka.dispatch.verification

import akka.actor.ActorCell,
       akka.actor.ActorSystem,
       akka.actor.ActorRef,
       akka.actor.LocalActorRef,
       akka.actor.ActorRefWithCell,
       akka.actor.Actor,
       akka.actor.PoisonPill,
       akka.actor.Props

import akka.dispatch.Envelope,
       akka.dispatch.MessageQueue,
       akka.dispatch.MessageDispatcher

import scala.collection.concurrent.TrieMap,
       scala.collection.mutable.Queue,
       scala.collection.mutable.HashMap,
       scala.collection.mutable.HashSet,
       scala.collection.mutable.ArrayBuffer,
       scala.collection.mutable.ArraySeq,
       scala.collection.mutable.Stack

import Function.tupled

import scalax.collection.mutable.Graph,
       scalax.collection.GraphEdge.DiEdge,
       scalax.collection.edge.LDiEdge
       
import com.typesafe.scalalogging.LazyLogging,
       org.slf4j.LoggerFactory,
       ch.qos.logback.classic.Level,
       ch.qos.logback.classic.Logger


// DPOR scheduler.
class DPORwFailures extends Scheduler with LazyLogging {
  
  final val SCHEDULER = "__SCHEDULER__"
  final val PRIORITY = "__PRIORITY__"
  
  var instrumenter = Instrumenter
  var externalEventList : Seq[ExternalEvent] = Vector()
  var externalEventIdx = 0
  var started = false
  
  var currentTime = 0
  var interleavingCounter = 0
  
  val pendingEvents = new HashMap[String, Queue[(Unique, ActorCell, Envelope)]]  
  val actorNames = new HashSet[String]
 
  val depGraph = Graph[Unique, DiEdge]()

  val quiescentPeriod = new HashMap[Unique, Int]
  
  val backTrack = new HashMap[Int, HashMap[(Unique, Unique), List[Unique]] ]
  var invariant : Queue[Unique] = Queue()
  var exploredTacker = new ExploredTacker
  
  val currentTrace = new Queue[Unique]
  val nextTrace = new Queue[Unique]
  var parentEvent = getRootEvent
  
  val reachabilityMap = new HashMap[String, Set[String]]
  
  var post: (Queue[Unique]) => Unit = nullFunPost
  var done: (Scheduler) => Unit = nullFunDone
  
  var currentQuiescentPeriod = 0
  var awaitingQuiescence = false
  var nextQuiescentPeriod = 0
  var quiescentMarker:Unique = null

  def nullFunPost(trace: Queue[Unique]) : Unit = {}
  def nullFunDone(s :Scheduler) : Unit = {}
  
  private[this] def awaitQuiescenceUpdate (nextEvent:Unique) = { 
    logger.trace(Console.BLUE + "Beginning to wait for quiescence " + Console.RESET)
    nextEvent match {
      case Unique(WaitQuiescence(), id) =>
        awaitingQuiescence = true
        nextQuiescentPeriod = id
        quiescentMarker = nextEvent
      case _ =>
        throw new Exception("Bad args")
    }
  }

  private[this] def addGraphNode (event: Unique) = {
    depGraph.add(event)
    quiescentPeriod(event) = currentQuiescentPeriod
  }
  
  def getRootEvent() : Unique = {
    var root = Unique(RootEvent, 0)
    addGraphNode(root)
    return root
  }
  
  
  def decomposePartitionEvent(event: NetworkPartition) : Queue[(String, NodesUnreachable)] = {
    val queue = new Queue[(String, NodesUnreachable)]
    queue ++= event.first.map { x => (x, NodesUnreachable(event.second)) }
    queue ++= event.second.map { x => (x, NodesUnreachable(event.first)) }
    return queue
  }
  
  
  def isSystemCommunication(sender: ActorRef, receiver: ActorRef) : Boolean =
    throw new Exception("not implemented")

  
  override def isSystemCommunication(sender: ActorRef, receiver: ActorRef, msg: Any): Boolean = 
  (receiver, sender) match {
    case (null, _) => return true
    case (_, null) => isSystemMessage("deadletters", receiver.path.name, msg)
    case _ => isSystemMessage(sender.path.name, receiver.path.name, msg)
  }

  
  // Is this message a system message
  def isValidActor(sender: String, receiver: String): Boolean = 
  ((actorNames contains sender) || (actorNames contains receiver)) match {
    case true => return true
    case _ => return false
  }
  
  
  def isSystemMessage(sender: String, receiver: String) : Boolean =
    throw new Exception("not implemented")
  
  
  // Is this message a system message
  override def isSystemMessage(sender: String, receiver: String, msg: Any): Boolean = {
    return !isValidActor(sender, receiver) || receiver == "deadLetters"
  }
  
  
  // Notification that the system has been reset
  def start_trace() : Unit = {
    
    started = false
    actorNames.clear
    externalEventIdx = 0
    
    currentTrace += getRootEvent()
    runExternal()
  }
  
  
  // When executing a trace, find the next trace event.
  def mutableTraceIterator( trace: Queue[Unique]) : Option[Unique] =
  trace.isEmpty match {
    case true => return None
    case _ => return Some(trace.dequeue)
  }
  
  

  // Get next message event from the trace.
  def getNextTraceMessage() : Option[Unique] = 
  mutableTraceIterator(nextTrace) match {
    // All spawn events are ignored.
    case some @ Some(Unique(s: SpawnEvent, id)) => getNextTraceMessage()
    // Root event needs to be ignored.
    case some @ Some(Unique(RootEvent, 0)) => getNextTraceMessage()
    case some @ Some(Unique(t, id)) => some
    case None => None
    case _ => throw new Exception("internal error")
  }

  
  
  
  // Figure out what is the next message to schedule.
  def schedule_new_message() : Option[(ActorCell, Envelope)] = {
    
    
    def checkInvariant[T1](result : Option[T1]) = result match {
    
      case Some((Unique(_, nID), _, _)) => invariant.headOption match {
          case Some(Unique(_, invID)) if (nID == invID) =>
            logger.trace( Console.RED + "Managed to replay the intended message: "+ nID + Console.RESET )
            invariant.dequeue()
          case _ =>
        }
        
      case _ =>
    }
    
    
    // Filter messages belonging to a particular actor.
    def equivalentTo(u1: Unique, other: (Unique, ActorCell, Envelope)) : 
    Boolean = (u1, other._1) match {
      
      case (Unique(MsgEvent(_, rcv1, _), id1),
            Unique(MsgEvent(_, rcv2, _), id2) ) =>
        // If the ID is zero, this means it's a system message.
        // In that case compare only the receivers.
        if (id1 == 0) rcv1 == rcv2
        else rcv1 == rcv2 && id1 == id2
        
      case (Unique(_, id1), Unique(_, id2) ) => id1 == id2  
      case _ => false
    }


    // Get from the current set of pending events.
    def getPendingEvent(): Option[(Unique, ActorCell, Envelope)] = {
      
      // Do we have some pending events
      Util.dequeueOne(pendingEvents) match {
        case Some( next @ (Unique(MsgEvent(snd, rcv, msg), id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing pending: " 
              + "(" + snd + " -> " + rcv + ") " +  + id + Console.RESET )
          Some(next)
          
        case Some(par @ (Unique(NetworkPartition(part1, part2), id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing the high level partition event " +
              id + Console.RESET)
          Some(par)

        case Some(qui @ (Unique(WaitQuiescence(), id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing the high level quiescence event " +
              id + Console.RESET)
          Some(qui)

        case None => None        
        case _ => throw new Exception("internal error")
      }
    }
    
    
    def getMatchingMessage() : Option[(Unique, ActorCell, Envelope)] = {
      getNextTraceMessage() match {
        // The trace says there is a message event to run.
        case Some(u @ Unique(MsgEvent(snd, rcv, msg), id)) =>

          // Look at the pending events to see if such message event exists. 
          pendingEvents.get(rcv) match {
            case Some(queue) => queue.dequeueFirst(equivalentTo(u, _))
            case None =>  None
          }
          
        case Some(u @ Unique(NetworkPartition(_, _), id)) =>
          
          // Look at the pending events to see if such message event exists. 
          pendingEvents.get(SCHEDULER) match {
            case Some(queue) => queue.dequeueFirst(equivalentTo(u, _))
            case None =>  None
          }

        case Some(u @ Unique(WaitQuiescence(), _)) => 
          // Look at the pending events to see if such message event exists. 
          pendingEvents.get(SCHEDULER) match {
            case Some(queue) => queue.dequeueFirst(equivalentTo(u, _))
            case None =>  None
          }
          
        // The trace says there is nothing to run so we have either exhausted our
        // trace or are running for the first time. Use any enabled transitions.
        case None => None
        case _ => throw new Exception("internal error")
      }
    
    }
    //
    // Are there any prioritized events that need to be dispatched.
    pendingEvents.get(PRIORITY) match {
      case Some(queue) if !queue.isEmpty => {
        val (_, cell, env) = queue.dequeue()
        return Some((cell, env))
      }
      case _ => None
    }
    
    
    val result = awaitingQuiescence match {
      case false =>
        getMatchingMessage() match {
          
          // There is a pending event that matches a message in our trace.
          // We call this a convergent state.
          case m @ Some((u @ Unique(MsgEvent(snd, rcv, msg), id), cell, env)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Message: " +
                "(" + snd + " -> " + rcv + ") " +  + id + Console.RESET )
            Some((u, cell, env))
            
          case Some((u @ Unique(NetworkPartition(part1, part2), id), _, _)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Partition: (" 
                + part1 + " <-> " + part2 + ")" + Console.RESET )
            Some((u, null, null))

          case Some((u @ Unique(WaitQuiescence(), id), _, _)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Quiescence: (" 
                + id +  ")" + Console.RESET )
            Some((u, null, null))
            
          // We call this a divergent state.
          case None => getPendingEvent()
          
          // Something went wrong.
          case _ => throw new Exception("not a message")
        }
      case true => // Don't call getMatchingMessage when waiting quiescence. Except when divergent or running the first
                  // time through, there should be no pending messages, signifying quiescence. Get pending event takes
                  // care of the first run. We could explicitly detect divergence here, but we haven't been so far.
        getPendingEvent()
    }
    
    
    
    checkInvariant(result)
    
    result match {
      
      case Some((nextEvent @ Unique(MsgEvent(snd, rcv, msg), nID), cell, env)) =>
      
        currentTrace += nextEvent
        (depGraph get nextEvent)
        parentEvent = nextEvent

        return Some((cell, env))
        
        
      case Some((nextEvent @ Unique(par@ NetworkPartition(_, _), nID), _, _)) =>

        // A NetworkPartition event is translated into multiple
        // NodesUnreachable messages which are atomically and
        // and invisibly consumed by all relevant parties.
        // Important: no messages are allowed to be dispatched
        // as the result of NodesUnreachable being received.
        decomposePartitionEvent(par) map tupled(
          (rcv, msg) => instrumenter().actorMappings(rcv) ! msg)
          
        instrumenter().tellEnqueue.await()
        
        currentTrace += nextEvent
        return schedule_new_message()

      case Some((nextEvent @ Unique(WaitQuiescence(), nID), _, _)) =>
        awaitQuiescenceUpdate(nextEvent)
        return schedule_new_message()
      case _ => return None
    }
    
  }
  
  
  def next_event() : Event = {
    throw new Exception("not implemented next_event()")
  }

  
  // Record that an event was consumed
  def event_consumed(event: Event) = {
  }
  
  
  def event_consumed(cell: ActorCell, envelope: Envelope) = {
  }
  
  
  def event_produced(event: Event) = event match {
      case event : SpawnEvent => actorNames += event.name
      case msg : MsgEvent => 
  }

  
  def runExternal() = {
    logger.trace(Console.RED + " RUN EXTERNAL CALLED initial IDX = " + externalEventIdx +Console.RESET) 
   
    var await = false
    while (externalEventIdx < externalEventList.length && !await) {
      val event = externalEventList(externalEventIdx)
      event match {
    
        case Start(propsCtor, name) => 
          instrumenter().actorSystem().actorOf(propsCtor(), name)
    
        case Send(rcv, msgCtor) =>
          val ref = instrumenter().actorMappings(rcv)
          instrumenter().actorMappings(rcv) ! msgCtor()

        case uniq @ Unique(par : NetworkPartition, id) =>  
          val msgs = pendingEvents.getOrElse(SCHEDULER, new Queue[(Unique, ActorCell, Envelope)])
          pendingEvents(SCHEDULER) = msgs += ((uniq, null, null))
          addGraphNode(uniq)
       
        case event @ Unique(WaitQuiescence(), _) =>
          val msgs = pendingEvents.getOrElse(SCHEDULER, new Queue[(Unique, ActorCell, Envelope)])
          pendingEvents(SCHEDULER) = msgs += ((event, null, null))
          addGraphNode(event)
          await = true

        // A unique ID needs to be associated with all network events.
        case par : NetworkPartition => throw new Exception("internal error")
        case _ => throw new Exception("unsuported external event")
      }
      externalEventIdx += 1
    }
    
    logger.trace(Console.RED + " RUN EXTERNAL LOOP ENDED idx = " + externalEventIdx + Console.RESET) 
    
    instrumenter().tellEnqueue.await()
    
    // Booststrap the process.
    schedule_new_message() match {
      case Some((cell, env)) =>
        instrumenter().dispatch_new_message(cell, env)
      case None => 
        throw new Exception("internal error")
    }
  }
        

  def run(externalEvents: Seq[ExternalEvent],
          f1: (Queue[Unique]) => Unit = nullFunPost,
          f2: (Scheduler) => Unit = nullFunDone) = {
    // Transform the original list of external events,
    // and assign a unique ID to all network events.
    // This is necessary since network events are not
    // part of the dependency graph.
    externalEventList = externalEvents.map { e => e match {
      case par: NetworkPartition => 
        val unique = Unique(par)
        unique
      case WaitQuiescence() =>
        Unique(WaitQuiescence())
      case other => other
    } }
    
    post = f1
    done = f2
    
    pendingEvents.clear()
    
    // In the end, reinitialize_system call start_trace.
    instrumenter().reinitialize_system(null, null)
  }
  
    /**
     * Given a message, figure out if we have already seen
     * it before. We achieve this by consulting the
     * dependency graph.
     *
     * * @param (cell, envelope: Original message context.
     *
     * * @return A unique event.
     */
  def getMessage(cell: ActorCell, envelope: Envelope) : Unique = {
    
    val snd = envelope.sender.path.name
    val rcv = cell.self.path.name
    val msg = new MsgEvent(snd, rcv, envelope.message)
    
    val parent = parentEvent match {
      case u @ Unique(m: MsgEvent, id) => u
      case u @ Unique(RootEvent, 0) => u
      case _ => throw new Exception("parent event not a message")
    }

    val inNeighs = depGraph.get(parent).inNeighbors
    inNeighs.find { x => x.value.event == msg } match {
      
      case Some(x) => return x.value
      case None =>
        val newMsg = Unique( MsgEvent(msg.sender, msg.receiver, msg.msg) )
        logger.trace(
            Console.YELLOW + "Not seen: " + newMsg.id + 
            " (" + msg.sender + " -> " + msg.receiver + ") " + Console.RESET)
        return newMsg
      case _ => throw new Exception("wrong type")
    }
      
  }
  
  
  
  def event_produced(cell: ActorCell, envelope: Envelope) = {

    envelope.message match {
    
      // Decomposed network events are simply enqueued to the priority queued
      // and dispatched at the earliest convenience.
      case par: NodesUnreachable =>
        val msgs = pendingEvents.getOrElse(PRIORITY, new Queue[(Unique, ActorCell, Envelope)])
        pendingEvents(PRIORITY) = msgs += ((null, cell, envelope))
        
      case _ =>
        val unique @ Unique(msg : MsgEvent , id) = getMessage(cell, envelope)
        val msgs = pendingEvents.getOrElse(msg.receiver, new Queue[(Unique, ActorCell, Envelope)])
        pendingEvents(msg.receiver) = msgs += ((unique, cell, envelope))
        
        logger.trace(Console.BLUE + "New event: " +
            "(" + msg.sender + " -> " + msg.receiver + ") " +
            id + Console.RESET)
        
        addGraphNode(unique)
        depGraph.addEdge(unique, parentEvent)(DiEdge)
    }
    


  }
  
  
  // Called before we start processing a newly received event
  def before_receive(cell: ActorCell) {
  }
  
  // Called after receive is done being processed 
  def after_receive(cell: ActorCell) {
  }

  
  def printPath(path : List[depGraph.NodeT]) : String = {
    var pathStr = ""
    for(node <- path) {
      node.value match {
        case Unique(m : MsgEvent, id) => pathStr += id + " "
        case _ => throw new Exception("internal error not a message")
      }
    }
    return pathStr
  }

  
  
  def notify_quiescence() {
    
    if (awaitingQuiescence) {
      awaitingQuiescence = false
      logger.trace(Console.BLUE + " Done waiting for quiescence " + Console.RESET)

      currentQuiescentPeriod = nextQuiescentPeriod
      nextQuiescentPeriod = 0

      addGraphNode(quiescentMarker)
      currentTrace += quiescentMarker
      quiescentMarker = null

      runExternal()
    } else {
      logger.info("\n--------------------- Interleaving #" +
                  interleavingCounter + " ---------------------")
      
      logger.debug(Console.BLUE + "Current trace: " +
          Util.traceStr(currentTrace) + Console.RESET)

      for (Unique(ev, id) <- currentTrace) 
        logger.debug(Console.BLUE + " " + id + " " + ev + Console.RESET)
      
      nextTrace.clear()
      
      dpor(currentTrace) match {
        case Some(trace) =>
          nextTrace ++= trace
          
          logger.debug(Console.BLUE + "Next trace:  " + 
              Util.traceStr(nextTrace) + Console.RESET)
          
          parentEvent = getRootEvent

          post(currentTrace)
          
          pendingEvents.clear()
          currentTrace.clear
          currentQuiescentPeriod = 0

          
          instrumenter().await_enqueue()
          instrumenter().restart_system()
        case None =>
          return
      }
    }
  }
  
  
  def enqueue_message(receiver: String,msg: Any): Unit = {
    throw new Exception("internal error not a message")
  }

  def notify_timer_cancel(receiver: ActorRef, msg: Any) {
    throw new RuntimeException("NYI!") // TODO(cs): do what DPORwHeuristics does..
  }

  
  
  def shutdown(): Unit = {
    throw new Exception("internal error not a message")
  }
  
  
  def getEvent(index: Integer, trace: Queue[Unique]) : Unique = {
    trace(index) match {
      case u: Unique => u 
      case _ => throw new Exception("internal error not a message")
    }
  }
  

  def dpor(trace: Queue[Unique]) : Option[Queue[Unique]] = {
    
    interleavingCounter += 1
    val root = getEvent(0, currentTrace)
    val rootN = ( depGraph get getRootEvent )
    
    val racingIndices = new HashSet[Integer]
    
    /**
     *  Analyze the dependency between two events that are co-enabled
     ** and have the same receiver.
     *
     ** @param earleirI: Index of the earlier event.
     ** @param laterI: Index of the later event.
     ** @param trace: The trace to which the events belong to.
     *
     ** @return none
     */
    def analyize_dep(earlierI: Int, laterI: Int, trace: Queue[Unique]): 
    Option[(Int, List[Unique])] = {

      // Retrieve the actual events.
      val earlier = getEvent(earlierI, trace)
      val later = getEvent(laterI, trace)

      // See if this interleaving has been explored.
      //val explored = exploredTacker.isExplored((later, earlier))
      //if (explored) return None

      (earlier.event, later.event) match {
        
        // Since the later event is completely independent, we
        // can simply move it in front of the earlier event.
        // This might cause the earlier event to become disabled,
        // but we have no way of knowing.
        case (_: MsgEvent,_: NetworkPartition) =>
          val branchI = earlierI
          val needToReplay = List(later, earlier)
            
          exploredTacker.setExplored(branchI, (earlier, later))

          return Some((branchI, needToReplay))
          
        // Similarly, we move an earlier independent event
        // just after the later event. None of the two event
        // will become disabled in this case.
        case (_: NetworkPartition, _: MsgEvent) => 
          val branchI = earlierI - 1
          val needToReplay = currentTrace.clone()
            .drop(earlierI + 1)
            .take(laterI - earlierI)
            .toList :+ earlier
          
          exploredTacker.setExplored(branchI, (earlier, later))
          
          return Some((branchI, needToReplay))
          
        case (_: MsgEvent, _: MsgEvent) =>
          // Get the actual nodes in the dependency graph that
          // correspond to those events
          val earlierN = (depGraph get earlier)
          val laterN = (depGraph get later)

          // Get the dependency path between later event and the
          // root event (root node) in the system.
          val laterPath = laterN.pathTo(rootN) match {
            case Some(path) => path.nodes.toList.reverse
            case None => throw new Exception("no such path")
          }

          // Get the dependency path between earlier event and the
          // root event (root node) in the system.
          val earlierPath = earlierN.pathTo(rootN) match {
            case Some(path) => path.nodes.toList.reverse
            case None => throw new Exception("no such path")
          }

          // Find the common prefix for the above paths.
          val commonPrefix = laterPath.intersect(earlierPath)

          // Figure out where in the provided trace this needs to be
          // replayed. In other words, get the last element of the
          // common prefix and figure out which index in the trace
          // it corresponds to.
          val lastElement = commonPrefix.last
          val branchI = trace.indexWhere { e => (e == lastElement.value) }

          val needToReplay = currentTrace.clone()
            .drop(branchI + 1)
            .dropRight(currentTrace.size - laterI - 1)
            .filter { x => x.id != earlier.id }

          require(branchI < laterI)

          // Since we're dealing with the vertices and not the
          // events, we need to extract the values.
          val needToReplayV = needToReplay.toList

          exploredTacker.setExplored(branchI, (earlier, later))
          
          return Some((branchI, needToReplayV))

      }

    }
    
    
    /** Figure out if two events are co-enabled.
     *
     * See if there is a path from the later event to the
     * earlier event on the dependency graph. If such
     * path does exist, this means that one event disables
     * the other one.
     * 
     ** @param earlier: First event
     ** @param later: Second event
     * 
     ** @return: Boolean 
     */
    def isCoEnabeled(earlier: Unique, later: Unique) : Boolean = (earlier, later) match {
      
      // NetworkPartition is always co-enabled with any other event.
      case (Unique(p : NetworkPartition, _), _) => true
      case (_, Unique(p : NetworkPartition, _)) => true
      // Quiescence is never co-enabled
      case (Unique(WaitQuiescence(), _), _) => false
      case (_, Unique(WaitQuiescence(), _)) => false
      // Root event is not coenabled
      case (Unique(RootEvent, _), _) => false
      case (_, Unique(RootEvent, _)) => false
      case (Unique(m1 : MsgEvent, _), Unique(m2 : MsgEvent, _)) =>
        if (m1.receiver != m2.receiver) 
          return false
        if (quiescentPeriod.get(earlier).get != quiescentPeriod.get(later).get) {
          return false
        }
        val earlierN = (depGraph get earlier)
        val laterN = (depGraph get later)
        
        val coEnabeled = laterN.pathTo(earlierN) match {
          case None => true
          case _ => false
        }
        
        return coEnabeled
    }
    

    /*
     * For every event in the trace (called later),
     * see if there is some earlier event, such that:
     * 
     * 0) They belong to the same receiver.
     * 1) They are co-enabled.
     * 2) Such interleaving hasn't been explored before.
     */ 
    for(laterI <- 0 to trace.size - 1) {
      val later @ Unique(laterEvent, laterID) = getEvent(laterI, trace)

      for(earlierI <- 0 to laterI - 1) {
        val earlier @ Unique(earlierEvent, earlierID) = getEvent(earlierI, trace) 
        
        //val sameReceiver = earlierMsg.receiver == laterMsg.receiver
        if ( isCoEnabeled(earlier, later)) {
          
          analyize_dep(earlierI, laterI, trace) match {
            case Some((branchI, needToReplayV)) =>    
              
              //logger.info(Console.GREEN +
              //  "Found a race between " + earlier.id + " and " +
              //  later.id + " with a common index " + branchI +
              //  Console.RESET)
              
              // Since we're exploring an already executed trace, we can
              // safely mark the interleaving of (earlier, later) as
              // already explored.
              backTrack.getOrElseUpdate(branchI, new HashMap[(Unique, Unique), List[Unique]])
              backTrack(branchI)((later, earlier)) = needToReplayV
              
            case None => // Nothing
          }
          
        }
        
      }
    }
    
    def getNext() : Option[(Int, (Unique, Unique), Seq[Unique])] = {
            // If the backtrack set is empty, this means we're done.
      if (backTrack.isEmpty) {
        logger.info("Tutto finito!")
        done(this)
        return None
        //System.exit(0);
      }
  
      val maxIndex = backTrack.keySet.max

      // If we have finished all explorations from this backtracking point, then remove it.
      backTrack(maxIndex).headOption match {
        case Some(((u1, u2), eventList)) => (u1, u2)
        case None => 
          backTrack.remove(maxIndex)
          return getNext()
        case _ => throw new Exception("invalid interleaving event types")
      }
      
      val ((e1, e2), replayThis) = backTrack(maxIndex).head
      backTrack(maxIndex).remove((e1, e2))
      
      exploredTacker.isExplored((e1, e2)) match {
        case true => return getNext()
        case false => return Some((maxIndex, (e1, e2), replayThis))
      }

    }

    getNext() match {
      case Some((maxIndex, (e1, e2), replayThis)) =>

        logger.info(Console.RED + "Exploring a new message interleaving " + 
           e1.id + " and " + e2.id  + " at index " + maxIndex + Console.RESET)
            
        
        exploredTacker.setExplored(maxIndex, (e1, e2))
        exploredTacker.trimExplored(maxIndex)
        exploredTacker.printExplored()
        
        // A variable used to figure out if the replay diverged.
        invariant = Queue(e1, e2)
        
        // Remove the backtrack branch, since we're about explore it now.
        if (backTrack(maxIndex).isEmpty)
          backTrack -= maxIndex
        
        // Return all events up to the backtrack index we're interested in
        // and slap on it a new set of events that need to be replayed in
        // order to explore that interleaving.
        return Some(trace.take(maxIndex + 1) ++ replayThis)
      case None =>
        return None
    }
  }
  

}
