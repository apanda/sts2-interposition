package akka.dispatch.verification

import scala.collection.mutable.HashMap


/**
 * User-defined fingerprint for uniquely describing how one or more safety
 * violations manifests.
 */
trait ViolationFingerprint {
  def matches(other: ViolationFingerprint) : Boolean
}

object TestOracle {
  // An predicate that returns None if the safety condition is not violated,
  // i.e. the execution is correct. Otherwise, returns a
  // `fingerprint` that identifies how the safety violation manifests itself.
  // The first argument is the current external event sequence, and the second
  // argument is a checkpoint map from actor -> Some(checkpointReply), or
  // actor -> None if the actor has crashed.
  type Invariant = (Seq[ExternalEvent], HashMap[String,Option[CheckpointReply]]) => Option[ViolationFingerprint]
}

trait TestOracle {
  type Invariant = (Seq[ExternalEvent], HashMap[String,Option[CheckpointReply]]) => Option[ViolationFingerprint]

  // Return one of:
  // {"RandomScheduler", "STSSchedNoPeek", "STSSched", "GreedyED", "DPOR", "FairScheduler"}
  def getName() : String

  def setInvariant(invariant: Invariant)

  /**
   * Returns Some(trace) if there exists any execution trace containing the given external
   * events that causes the given invariant violation to reappear.
   * Otherwise, returns None.
   *
   * At the end of the invocation, it is the responsibility of the TestOracle
   * to ensure that the ActorSystem is returned to a clean initial state.
   * Throws an IllegalArgumentException if setInvariant has not been invoked.
   */
  // Note that return value of this function is the opposite of what we
  // describe in the paper...
  def test(events: Seq[ExternalEvent],
           violation_fingerprint: ViolationFingerprint,
           stats: MinimizationStats) : Option[EventTrace]
}

object StatelessTestOracle {
  type OracleConstructor = () => TestOracle
}

/*
 * A TestOracle that throws away all state between invocations of test().
 * Useful for debugging or avoiding statefulness problems, e.g. deadlocks.
 */
// TODO(cs): there is currently a deadlock in PeekScheduler: if you invoke
// PeekScheduler.test() multiple times, it runs the first execution just fine,
// but the second execution never reaches Quiescence, and blocks infinitely
// trying to acquire traceSem. Figure out why.
class StatelessTestOracle(oracle_ctor: StatelessTestOracle.OracleConstructor) extends TestOracle {
  var invariant : Invariant = null

  def getName: String = oracle_ctor().getName

  def setInvariant(inv: Invariant) = {
    invariant = inv
  }

  def test(events: Seq[ExternalEvent],
           violation_fingerprint: ViolationFingerprint,
           stats: MinimizationStats) : Option[EventTrace] = {
    val oracle = oracle_ctor()
    try {
      Instrumenter().scheduler = oracle.asInstanceOf[Scheduler]
    } catch {
      case e: Exception => println("oracle not a scheduler?")
    }
    oracle.setInvariant(invariant)
    val result = oracle.test(events, violation_fingerprint, stats)
    Instrumenter().restart_system
    return result
  }
}
