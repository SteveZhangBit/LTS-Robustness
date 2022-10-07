import cmu.isr.ts.*
import cmu.isr.ts.lts.CompactDetLTS
import cmu.isr.ts.lts.asLTS
import cmu.isr.ts.lts.CompactLTS
import cmu.isr.ts.lts.checkSafety
import cmu.isr.ts.lts.ltsa.LTSACall
import cmu.isr.ts.lts.ltsa.LTSACall.asDetLTS
import cmu.isr.ts.lts.ltsa.LTSACall.asLTS
import cmu.isr.ts.lts.ltsa.LTSACall.compose
import cmu.isr.ts.lts.makeErrorState
import cmu.isr.ts.nfa.NFAParallelComposition
import cmu.isr.ts.nfa.determinise
import net.automatalib.automata.fsa.NFA
import net.automatalib.automata.fsa.impl.compact.CompactDFA
import net.automatalib.util.automata.builders.AutomatonBuilders
import net.automatalib.words.Alphabet
import net.automatalib.words.impl.Alphabets
import java.io.File
import java.util.*

fun QfProjE(src : Pair<Int, Int>,
            E : LTS<Int, String>,
            F : NFAParallelComposition<Int, Int, String>)
        : Set<Pair<Int,Int>> {
    val proj = mutableSetOf<Pair<Int,Int>>()
    for (a in E.alphabet()) {
        for (dst in F.getStates(E.alphabet())) {
            if (F.getTransitions(src, a).contains(dst) && E.getTransitions(src.first, a).contains(dst.first))  {
                proj.add(dst)
            }
        }
    }
    return proj
}

fun safe(E : LTS<Int, String>,
         F : NFAParallelComposition<Int, Int, String>,
         CPre_iminus1 : Set<Pair<Int, Int>>)
        : Set<Pair<Int, Int>> {
    val CPre_i = F.getStates(E.alphabet()).filter { CPre_iminus1.containsAll(QfProjE(it, E, F)) }.toSet()
    //val CPre_i = CPre_iminus1.filter { CPre_iminus1.containsAll(QfProjE(it, E, F)) }.toSet()
    return if (CPre_i == CPre_iminus1) {
        CPre_i
    }
    else {
        safe(E, F, CPre_i).intersect(CPre_iminus1)
    }
}

/**
 * Finds all transitions in T
 */

fun ltsTransitions(T : LTS<Int, String>) : Set<Triple<Int,String,Int>> {
    val transitions = mutableSetOf<Triple<Int,String,Int>>()
    for (src in T) {
        for (a in T.alphabet()) {
            for (dst in T.getTransitions(src, a)) {
                transitions.add(Triple(src, a, dst))
            }
        }
    }
    return transitions
}

fun ltsTransitions(T : NFAParallelComposition<Int, Int, String>, alph : Alphabet<String>) : Set<Triple<Pair<Int,Int>,String,Pair<Int,Int>>> {
    val transitions = mutableSetOf<Triple<Pair<Int,Int>,String,Pair<Int,Int>>>()
    for (src in T.getStates(alph)) {
        for (a in alph) {
            for (dst in T.getTransitions(src, a)) {
                transitions.add(Triple(src, a, dst))
            }
        }
    }
    return transitions
}

/**
 * It's ridiculous how tough they make it to copy.
 * The algorithm makes a key assumption: that the initial state in a DFA has the lowest state ID (presumably 0), and it
 * will be added to newLTS with the same lowest state ID.
 */
fun copyLTS(T : CompactDetLTS<String>) : CompactDetLTS<String> {
    val newDFA = AutomatonBuilders.newDFA(T.inputAlphabet)
        .withInitial(T.initialState)
        .create()
    for (s in T.states) {
        if (T.initialState == s) {
            newDFA.setAccepting(s, T.isAccepting(s))
        }
        else {
            newDFA.addState(T.isAccepting(s))
        }
    }
    for (t in ltsTransitions(T)) {
        newDFA.addTransition(t.first, t.second, t.third)
    }
    return newDFA.asLTS()
}

/**
 * Makes a copy of a NFA (in particular, a CompactLTS). This algorithm does not rely on as many assumptions as the
 * DFA copyLTS, but it does assume that T.states iterates on the state IDs in order, and T.addInitialState() and
 * T.addState() add new state IDs to T in order.
 */
fun copyLTS(T : CompactLTS<String>) : CompactLTS<String> {
    val newNFA = AutomatonBuilders.newNFA(T.inputAlphabet).create()
    for (s in T.states) {
        if (T.initialStates.contains(s)) {
            newNFA.addInitialState(T.isAccepting(s))
        }
        else {
            newNFA.addState(T.isAccepting(s))
        }
    }
    for (t in ltsTransitions(T)) {
        newNFA.addTransition(t.first, t.second, t.third)
    }
    return newNFA.asLTS()
}

fun copyLTSFull(T : CompactLTS<String>) : CompactLTS<String> {
    val newNFA = AutomatonBuilders.newNFA(T.inputAlphabet).create()
    for (s in T.states) {
        if (T.initialStates.contains(s)) {
            newNFA.addInitialState(T.isAccepting(s))
        }
        else {
            newNFA.addState(T.isAccepting(s))
        }
    }
    for (src in T.states) {
        for (a in T.alphabet()) {
            for (dst in T.states) {
                newNFA.addTransition(src, a, dst)
            }
        }
    }
    return newNFA.asLTS()
}

fun copyLTSAcceptingOnly(T : CompactLTS<String>) : NFA<Int, String> {
    val newNFA = AutomatonBuilders.newNFA(T.inputAlphabet).create()
    for (s in T.states) {
        if (T.isAccepting(s)) {
            if (T.initialStates.contains(s)) {
                newNFA.addInitialState(T.isAccepting(s))
            } else {
                newNFA.addState(T.isAccepting(s))
            }
        }
    }
    for (t in ltsTransitions(T)) {
        if (newNFA.states.contains(t.first) && newNFA.states.contains(t.third))
        newNFA.addTransition(t.first, t.second, t.third)
    }
    return newNFA
}

/**
 * Turns an NFA (T) into a DFA
 */
fun toDeterministic(T : CompactLTS<String>) : MutableDetLTS<Int, String> {
    val det = determinise(T) as CompactDFA<String>
    val detLts = CompactDetLTS(det)
    return detLts as MutableDetLTS<Int, String>
}

/**
 * returns whether:
 * T |= P
 */
fun satisfies(T : LTS<Int, String>, P : MutableDetLTS<Int,String>) : Boolean {
    // TODO really should make a copy of P
    val pFixed = makeErrorState(P)
    val result = checkSafety(T, pFixed)
    return !result.violation
}

/**
 * Computes the cartesian product:
 *   Q X αT X Q
 * Presumably T is some LTS whose state space is Q, but this is not required.
 */
fun <T> product(src : Collection<T>, alphabet : Set<String>, dst : Collection<T>) : MutableSet<Triple<T, String, T>> {
    val perturbations : MutableSet<Triple<T,String,T>> = mutableSetOf()
    for (s in src) {
        for (a in alphabet) {
            for (d in dst) {
                perturbations.add(Triple(s,a,d))
            }
        }
    }
    return perturbations
}

fun <T> powerset(s : Set<T>) : Set<Set<T>> {
    val ps = mutableSetOf<Set<T>>(emptySet())
    for (e in s) {
        val toAdd = mutableSetOf<Set<T>>()
        for (p in ps) {
            toAdd.add(p + e)
        }
        ps += toAdd
    }
    return ps
}

fun stripTauTransitions(T : CompactLTS<String>) : CompactLTS<String> {
    val newAlphabet = Alphabets.fromCollection(T.inputAlphabet.toSet() - "tau")
    val newNFA = AutomatonBuilders.newNFA(newAlphabet).create()
    for (s in T.states) {
        if (T.initialStates.contains(s)) {
            newNFA.addInitialState(T.isAccepting(s))
        }
        else {
            newNFA.addState(T.isAccepting(s))
        }
    }
    for (t in ltsTransitions(T)) {
        if (t.second != "tau") {
            newNFA.addTransition(t.first, t.second, t.third)
        }
    }
    return newNFA.asLTS()

    /*
    for (src in T.states) {
        for (dst in T.getTransitions(src, "tau")) {
            T.removeTransition(src, "tau", dst)
        }
    }
     */
}

fun fspToDFA(path: String) : CompactDetLTS<String> {
    val spec = File(path).readText()
    val composite = LTSACall.compile(spec).compose()
    return composite.asDetLTS() as CompactDetLTS
}

fun fspToNFA(path: String) : CompactLTS<String> {
    val spec = File(path).readText()
    val composite = LTSACall.compile(spec).compose()
    return composite.asLTS() as CompactLTS
}
