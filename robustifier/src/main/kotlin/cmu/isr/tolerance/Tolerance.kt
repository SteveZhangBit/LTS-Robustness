package cmu.isr.tolerance

import addPerturbations
import atLeastAsPowerful
import cmu.isr.ts.LTS
import cmu.isr.ts.alphabet
import cmu.isr.ts.lts.*
import cmu.isr.ts.lts.ltsa.write
import cmu.isr.ts.nfa.NFAParallelComposition
import cmu.isr.ts.parallel
import copyLTS
import copyLTSAcceptingOnly
import copyLTSFull
import fspToDFA
import fspToNFA
import ltsTransitions
import net.automatalib.util.automata.builders.AutomatonBuilders
import net.automatalib.words.Alphabet
import net.automatalib.words.impl.Alphabets
import powerset
import product
import safe
import satisfies
import stripTauTransitions
import java.util.*

fun <T> allPerturbations(states : Collection<T>, alphabet : Alphabet<String>) : Set<Set<Triple<T, String, T>>> {
    fun pertHelper(perturbations : MutableSet<MutableSet<Triple<T,String,T>>>,
                   powerset : Set<Triple<T,String,T>>) {
        if (powerset.isNotEmpty()) {
            val elem = powerset.first()
            pertHelper(perturbations, powerset - elem)

            val dPlusElems = mutableSetOf<MutableSet<Triple<T, String, T>>>()
            for (d in perturbations) {
                dPlusElems += (d + elem) as MutableSet<Triple<T, String, T>>
            }
            perturbations += dPlusElems
        }
    }
    val perturbations : MutableSet<MutableSet<Triple<T,String,T>>> = mutableSetOf(mutableSetOf())
    val powerset = product(states, alphabet.toMutableSet(), states)
    pertHelper(perturbations, powerset)
    return perturbations
}

fun deltaNaiveBruteForce(E : CompactLTS<String>,
                         C : CompactLTS<String>,
                         P : CompactDetLTS<String>)
                        : Set<Set<Triple<Int,String,Int>>> {
    val delta = DeltaBuilder()
    val QXActXQ = allPerturbations(E.states, E.alphabet())

    for (d in QXActXQ) {
        val Ed = addPerturbations(E, d)
        val EdCompC = parallel(Ed, C)
        if (satisfies(EdCompC, P)) {
            delta += d
        }
    }
    //println("#delta before: ${delta.size}")

    return delta.toSet()
}

fun acceptingStates(F : NFAParallelComposition<Int, Int, String>,
                    nfaF : LTS<Int, String>,
                    E : CompactLTS<String>,
                    P : LTS<Int, String>)
                    : Set<Pair<Int, Int>> {
    return F.getStates(nfaF.alphabet())
        .filter { E.isAccepting(it.first) && P.isAccepting(it.second) }
        .toSet()
}

fun deltaBruteForce(E : CompactLTS<String>, C : CompactLTS<String>, P : CompactDetLTS<String>) : Set<Set<Triple<Int,String,Int>>> {
    val Efull = copyLTSFull(E)
    val ltsCCompP = parallel(C, P)
    val nfaF = parallel(Efull, ltsCCompP)
    val F = NFAParallelComposition(Efull, ltsCCompP)
    val QfMinusErr = acceptingStates(F, nfaF, E, ltsCCompP)
    val W = safe(E, F, QfMinusErr)
    println("#W: ${W.size}")

    //val Re = ltsTransitions(E)
    val ltsECompC = parallel(E, C)
    val ECompC = NFAParallelComposition(E, C)
    val Re = ltsTransitions(ECompC, ltsECompC.alphabet())
        .map { Triple(it.first.first, it.second, it.third.first) }
        .toSet()
    val Rf = ltsTransitions(F, nfaF.alphabet())
    val A = product(E.states, E.inputAlphabet.toSet(), E.states)
    val delta = DeltaBuilder()
    for (S in powerset(W)) {
        val SxActxS = product(S, nfaF.alphabet().toSet(), S)
        val Rt = Rf.filter { SxActxS.contains(it) }
        val RtProjE = Rt.map { Triple(it.first.first,it.second,it.third.first) }.toSet()

        val del = Rf
            .filter { S.contains(it.first) && !S.contains(it.third) }
            .map { Triple(it.first.first, it.second, it.third.first) }
            .toSet()
        val deltaCandidate = A - del
        if (RtProjE.containsAll(Re) && deltaCandidate.containsAll(Re)) {
            delta.add(deltaCandidate)
        }
    }

    return delta.toSet()
}

fun outgoingStates(S : Set<Pair<Int,Int>>, F : NFAParallelComposition<Int,Int,String>, nfaF : LTS<Int,String>) : Set<Pair<Int,Int>> {
    val outgoing = mutableSetOf<Pair<Int,Int>>()
    for (src in S) {
        for (a in nfaF.alphabet()) {
            outgoing.addAll(F.getTransitions(src, a))
        }
    }
    return outgoing
}

fun heuristicSubsets(W : Set<Pair<Int, Int>>,
                     F : NFAParallelComposition<Int,Int,String>,
                     nfaF : LTS<Int,String>,
                     F_notfull : NFAParallelComposition<Int,Int,String>,
                     nfaF_notfull : LTS<Int,String>)
                     : Set<Set<Pair<Int, Int>>> {
    val Rfnf = ltsTransitions(F_notfull, nfaF_notfull.alphabet())
    val nec = W intersect (Rfnf.map { it.first } union Rfnf.map { it.third })
    val queue : Queue<Set<Pair<Int, Int>>> = LinkedList()
    queue.add(nec)

    val subsets = mutableSetOf<Set<Pair<Int, Int>>>()
    while (queue.isNotEmpty()) {
        val S = queue.remove()
        if (!subsets.contains(S)) {
            subsets.add(S)
            val dstStates = (outgoingStates(S, F, nfaF) - S) intersect W
            for (additionalStates in powerset(dstStates)) {
                val Sprime = S union additionalStates
                queue.add(Sprime)
            }
        }
    }

    return subsets
}

fun deltaHeuristic(E : CompactLTS<String>, C : CompactLTS<String>, P : CompactDetLTS<String>) : Set<Set<Triple<Int,String,Int>>> {
    val Efull = copyLTSFull(E)
    val ltsCCompP = parallel(C, P)
    val nfaF = parallel(Efull, ltsCCompP)
    val F = NFAParallelComposition(Efull, ltsCCompP)
    val QfMinusErr = acceptingStates(F, nfaF, E, ltsCCompP)
    val W = safe(E, F, QfMinusErr)
    println("#W: ${W.size}")

    //val Re = ltsTransitions(E)
    val ltsECompC = parallel(E, C)
    val ECompC = NFAParallelComposition(E, C)
    val RecProjE = ltsTransitions(ECompC, ltsECompC.alphabet())
        .map { Triple(it.first.first, it.second, it.third.first) }
        .toSet()
    val Rf = ltsTransitions(F, nfaF.alphabet())
    val A = product(E.states, E.inputAlphabet.toSet(), E.states)
    val delta = DeltaBuilder()

    val F_notfull = NFAParallelComposition(E, ltsCCompP)
    val nfaF_notfull = parallel(E, ltsCCompP)
    //val subsets = heuristicSubsets(W, F, nfaF, F_notfull, nfaF_notfull)
    val subsets = powerset(W)
    println("#subsets: ${subsets.size}")
    //println("subsets: $subsets")

    //println("Rf: ${Rf.joinToString { "$it\n" }}")
    //println()
    for (S in subsets) {
        val SxActxS = product(S, nfaF.alphabet().toSet(), S)
        val Rt = Rf.filter { SxActxS.contains(it) }
        val RtProjE = Rt.map { Triple(it.first.first,it.second,it.third.first) }.toSet()

        //val del = Rf
        val del = (Rf - Rt)
            .filter { S.contains(it.first) && !S.contains(it.third) }
            .map { Triple(it.first.first, it.second, it.third.first) }
            .toSet()
        val deltaCandidate = A - del
        //delta.add(deltaCandidate)
        if (RtProjE.containsAll(RecProjE) && deltaCandidate.containsAll(RecProjE)) {
            delta.add(deltaCandidate)
            if (deltaCandidate.contains(Triple(1,"a",2))) {
                println("subset: $S")
                println("Rt: $Rt")
                println("deltaCandidate: $deltaCandidate")
                println()
            }
        }
    }

    return delta.toSet()
}


fun main(args : Array<String>) {
    /*
    val T = AutomatonBuilders.newNFA(Alphabets.fromArray("a"))
        .withInitial(0)
        .from(0).on("a").to(1)
        .withAccepting(0, 1, 2)
        .create()
        .asLTS()
    val P = AutomatonBuilders.newDFA(Alphabets.fromArray("a"))
        .withInitial(0)
        .from(0).on("a").to(1)
        .from(1).on("a").to(2)
        //.from(0).on("b").to(0)
        .withAccepting(0, 1, 2)
        .create()
        .asLTS()
    write(System.out, P, P.alphabet())
    */

    if (args.size < 4) {
        println("usage: tolerance <alg> <env> <ctrl> <prop>")
        return
    }

    val alg = args[0]
    val E = stripTauTransitions(fspToNFA(args[1]))
    val C = stripTauTransitions(fspToNFA(args[2]))
    val P = fspToDFA(args[3])

    val delta =
        when (alg) {
            "0" -> deltaNaiveBruteForce(E, C, P)
            "1" -> deltaBruteForce(E, C, P)
            "2" -> deltaHeuristic(E, C, P)
            else -> {
                println("Invalid algorithm")
                return
            }
        }

    // prints delta
    println("#delta: ${delta.size}")
    for (d in delta) {
        val sortedD = d.sortedWith { a, b ->
            if (a.second != b.second) {
                a.second.compareTo(b.second)
            }
            else if (a.first != b.first) {
                a.first - b.first
            }
            else {
                a.third - b.third
            }
        }
        println("  {${sortedD.joinToString()}}")
        //println("  {${d.joinToString()}}")
    }

    // print the FSP for each Ed
    for (d in delta) {
        println()
        val Ed = copyLTSAcceptingOnly(addPerturbations(E, d))
        write(System.out, Ed, Ed.alphabet())
    }

    // checks to make sure the solution is sound
    var sound = true
    for (d in delta) {
        val Ed = addPerturbations(E, d)
        val EdComposeC = parallel(Ed, C)
        if (!satisfies(EdComposeC, P)) {
            sound = false
            println("Violation for Ed||P |= P: $d")
        }
    }
    if (sound) {
        println()
        println("Solution is sound")
    }
}
