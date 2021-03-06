package cmu.isr.robustify.supervisory

import cmu.isr.utils.combinations
import cmu.isr.utils.pretty
import net.automatalib.automata.fsa.impl.compact.CompactDFA
import net.automatalib.words.Word
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

private class SolutionCandidate(
  val sup: CompactSupDFA<String>,
  val preferred: Collection<Word<String>>,
  val utilityPreferred: Int,
  val utilityCost: Int
  )


class SolutionIterator(
  private val problem: SupervisoryRobustifier,
  private val alg: Algorithms,
  private val deadlockFree: Boolean,
  private val maxIter: Int
) : Iterable<CompactDFA<String>>, Iterator<CompactDFA<String>> {

  private val logger = LoggerFactory.getLogger(javaClass)
  private lateinit var weights: WeightsMap
  private lateinit var initSup: CompactSupDFA<String>
  private lateinit var preferredIterator: PreferredBehIterator<String>
  private lateinit var maxPreferred: Collection<Word<String>>
  private var minCost = Int.MIN_VALUE
  private var synthesisCounter = 0
  private val solutions: Deque<CompactDFA<String>> = ArrayDeque()
  private var curIter = 0

  override fun iterator(): Iterator<CompactDFA<String>> {
    val startTime = System.currentTimeMillis()
    logger.info("==============================>")
    logger.info("Initializing search by using $alg search...")

    // flatten the preferred behaviors
    val preferred = problem.preferredMap.flatMap { it.value }
    logger.info("Number of preferred behaviors: ${preferred.size}")

    // compute weight map
    weights = problem.computeWeights()
    logger.debug("Preferred behavior weights:")
    for ((p, w) in weights.preferred)
      logger.debug("\t${w}: $p")
    logger.debug("Controllable weights:")
    for ((input, w) in weights.controllable)
      logger.debug("\t${w}: $input")
    logger.debug("Observable weights:")
    for ((input, w) in weights.observable)
      logger.debug("\t${w}: $input")

    // get controllable and observable events
    val controllable = weights.controllable.keys
    val observable = weights.observable.keys
    logger.info("Number of controllable events with cost: ${controllable.size - (problem.controllableMap[Priority.P0]?.size ?: 0)}")
    logger.info("Number of observable events with cost: ${observable.size - (problem.observableMap[Priority.P0]?.size ?: 0)}")

    // synthesize a supervisor with the max controllable and observable events
    val sup = problem.supervisorySynthesize(controllable, observable)
    if (sup == null) {
      logger.warn("No supervisor found with max controllable and observable events.")
      return emptyArray<CompactDFA<String>>().iterator()
    }

    // compute the maximum fulfilled preferred behavior under the max controllability and observability
    maxPreferred = problem.checkPreferred(sup, preferred)
    logger.info("Maximum fulfilled preferred behavior:")
    for (p in maxPreferred)
      logger.info("\t$p")

    // remove those absolutely unused controllable and observable events which generates the initial solution
    initSup = problem.removeUnnecessary(sup)

    preferredIterator = PreferredBehIterator(problem.preferredMap)

    logger.info("Initialization completes, time: ${Duration.ofMillis(System.currentTimeMillis() - startTime).pretty()}")
    logger.info("Start search from events:")
    logger.info("Controllable: ${initSup.controllable}")
    logger.info("Observable: ${initSup.observable}")

    return this
  }

  override fun hasNext(): Boolean {
    while (solutions.isEmpty() && curIter < maxIter && preferredIterator.hasNext()) {
      nextIteration()
      curIter++
    }
    return solutions.isNotEmpty()
  }

  override fun next(): CompactDFA<String> {
    return solutions.poll()
  }

  private fun nextIteration() {
    val startTime = System.currentTimeMillis()
    logger.info("==============================>")
    logger.info("Start iteration ${curIter+1}...")

    // the list of preferred behavior sets, each set has the same utility value
    val removeSet = preferredIterator.next()
    var minBracketCost = Int.MIN_VALUE
    var candidates = mutableListOf<SolutionCandidate>()
    synthesisCounter = 0

    for (toRemove in removeSet) {
      logger.info("Try to weaken the preferred behavior by one of the ${toRemove.size} behavior sets:")
      for (p in toRemove)
        logger.info("\t$p")

      // remove preferred behavior for less cost
      val preferred = maxPreferred - toRemove.toSet()
      // minimizing the controller by removing controllable/observable events
      // there might be more than one combination of events with the same cost of the given preferred behavior
      for (sup in minimize(preferred)) {
        val (utilityPreferred, utilityCost) = computeUtility(preferred, sup.controllable, sup.observable)
        if (utilityCost < minBracketCost)
          continue
        val candidate = SolutionCandidate(sup, preferred, utilityPreferred, utilityCost)
        // if cost is better, then clear out all others, update best cost and then start a new list
        if (utilityCost > minBracketCost) {
          minBracketCost = utilityCost
          candidates = mutableListOf(candidate)
        } else { // if cost is same as best, then add to list
          candidates.add(candidate)
        }
      }
    }

    logger.info("This iteration completes, time: ${Duration.ofMillis(System.currentTimeMillis() - startTime).pretty()}")
    logger.info("Number of controller synthesis process invoked: $synthesisCounter")

    if (minBracketCost > minCost) {
      minCost = minBracketCost
      for (candidate in candidates) {
        val solution = problem.buildSys(problem.constructSupervisor(candidate.sup))
        if (alg == Algorithms.Pareto)
          logger.info("New pareto-optimal found:")
        else
          logger.info("New solution found:")
        logger.info("\tControllable: ${candidate.sup.controllable}")
        logger.info("\tObservable: ${candidate.sup.observable}")
        logger.info("\tPreferred Behavior:")
        for (p in candidate.preferred)
          logger.info("\t\t$p")
        logger.info("Utility Preferred Behavior: ${candidate.utilityPreferred}")
        logger.info("Utility Cost: ${candidate.utilityCost}")
        solutions.offer(solution)
      }
    } else {
      logger.info("No new solution found in iteration ${curIter+1}.")
    }
  }

  private fun minimize(preferred: Collection<Word<String>>): Collection<CompactSupDFA<String>> {
    return when (alg) {
      Algorithms.Pareto -> minimizePareto(preferred)
      Algorithms.Fast -> minimizeFast(preferred)
    }
  }

  private fun minimizeFast(preferred: Collection<Word<String>>,
                           deterministic: Boolean = true): Collection<CompactSupDFA<String>> {
    val minimizeSeq = mutableListOf<Pair<String, Char>>()
    for (p in listOf(Priority.P3, Priority.P2, Priority.P1)) {
      minimizeSeq.addAll(
        initSup.controllable
          .filter { it in (problem.controllableMap[p] ?: emptySet()) }
          .let { if (deterministic) it.sorted() else it }
          .map { Pair(it, 'c') }
      )
      minimizeSeq.addAll(
        initSup.observable
          .filter { it in (problem.observableMap[p] ?: emptySet()) }
          .let { if (deterministic) it.sorted() else it }
          .map { Pair(it, 'o') }
      )
    }

    var minSup = initSup
    for ((input, t) in minimizeSeq) {
      var controllable = minSup.controllable
      var observable = minSup.observable
      if (t == 'c' && controllable.size > 1)
        controllable -= input
      else if (t == 'o' && input !in controllable)
        observable -= input
      else
        continue

      logger.debug("Try removing the following events:")
      logger.debug("Controllable: ${initSup.controllable - controllable.toSet()}")
      logger.debug("Observable: ${initSup.observable - observable.toSet()}")

      val sup = problem.supervisorySynthesize(controllable, observable)
      synthesisCounter++
      if (sup == null)
        continue
      if (problem.satisfyPreferred(sup, preferred))
        minSup = sup
    }
    return listOf(minSup)
  }

  private fun minimizePareto(preferred: Collection<Word<String>>): Collection<CompactSupDFA<String>> {
    val eventsMap = mutableMapOf<Priority, Pair<Collection<String>, Collection<String>>>()
    for (p in listOf(Priority.P3, Priority.P2, Priority.P1)) {
      eventsMap[p] = Pair(
        problem.controllableMap[p]?.intersect(initSup.controllable.toSet()) ?: emptySet(),
        problem.observableMap[p]?.intersect(initSup.observable.toSet()) ?: emptySet()
      )
    }

    // the list of combinations of events that minimize the controller s.t. the given preferred behavior is satisfied
    var minSups = mutableListOf(initSup)
    var lastNonEmptySups = minSups
    for (p in listOf(Priority.P3, Priority.P2, Priority.P1)) {
      val (canRemoveCtrl, canRemoveObsrv) = eventsMap[p]!!
      var removedCounter = 0

      while (minSups.isNotEmpty() && removedCounter++ < canRemoveCtrl.size + canRemoveObsrv.size) {
        lastNonEmptySups = minSups
        minSups = mutableListOf()
        for ((controllable, observable) in removeOneEventFrom(lastNonEmptySups, canRemoveCtrl, canRemoveObsrv)) {
          logger.debug("Try removing the following events:")
          logger.debug("Controllable: ${initSup.controllable - controllable.toSet()}")
          logger.debug("Observable: ${initSup.observable - observable.toSet()}")

          val sup = problem.supervisorySynthesize(controllable, observable)
          synthesisCounter++
          if (sup == null)
            continue
          // add minimization if preferred behavior maintained
          if (problem.satisfyPreferred(sup, preferred)) {
            minSups.add(sup)
          }
        }
      }

      if (minSups.isEmpty())
        minSups = lastNonEmptySups
    }

    return minSups
  }

  private fun removeOneEventFrom(
    sups: Collection<CompactSupDFA<String>>,
    canRemoveCtrl: Collection<String>,
    canRemoveObsrv: Collection<String>
  ): Collection<Pair<Collection<String>, Collection<String>>> {
    val l = mutableSetOf<Pair<Collection<String>, Collection<String>>>()

    for (sup in sups) {
      for (input in canRemoveCtrl) {
        if (input in sup.controllable && sup.controllable.size > 1) { // avoid removing all controllable
          l.add(Pair(
            sup.controllable - input,
            sup.observable
          ))
        }
      }

      for (input in canRemoveObsrv) {
        if (input in sup.observable && input !in sup.controllable) {
          l.add(Pair(
            sup.controllable,
            sup.observable - input
          ))
        }
      }
    }

    return l
  }

  private fun computeUtility(preferred: Collection<Word<String>>, controllable: Collection<String>,
                             observable: Collection<String>): Pair<Int, Int> {
    return Pair(
      preferred.sumOf { weights.preferred[it]!! },
      controllable.sumOf { weights.controllable[it]!! } + observable.sumOf { weights.observable[it]!! }
    )
  }

}


class PreferredBehIterator<I>(
  private val preferredMap: Map<Priority, Collection<Word<I>>>
) : Iterator<Collection<Collection<Word<I>>>> {

  private var p1Counter = 0
  private var p2Counter = 0
  private var p3Counter = 0

  override fun hasNext(): Boolean {
    return p1Counter <= (preferredMap[Priority.P1]?.size ?: 0) ||
        p2Counter <= (preferredMap[Priority.P2]?.size ?: 0) ||
        p3Counter <= (preferredMap[Priority.P3]?.size ?: 0)
  }

  override fun next(): Collection<Collection<Word<I>>> {
    val p1s = preferredMap[Priority.P1]?.toList()?.combinations(p1Counter) ?: listOf(emptyList())
    val p2s = preferredMap[Priority.P2]?.toList()?.combinations(p2Counter) ?: listOf(emptyList())
    val p3s = preferredMap[Priority.P3]?.toList()?.combinations(p3Counter) ?: listOf(emptyList())
    val removeList = mutableListOf<Collection<Word<I>>>()
    for (a in p1s)
      for (b in p2s)
        for (c in p3s)
          removeList.add(a + b + c)

    if (++p1Counter <= (preferredMap[Priority.P1]?.size ?: 0)) {

    } else if (++p2Counter <= (preferredMap[Priority.P2]?.size ?: 0)) {
      p1Counter = 0
    } else if (++p3Counter <= (preferredMap[Priority.P3]?.size ?: 0)) {
      p1Counter = 0
      p2Counter = 0
    }

    return removeList
  }

}