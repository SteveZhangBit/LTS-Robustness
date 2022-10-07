package cmu.isr.tolerance

class DeltaBuilder {
    private var delta = mutableSetOf<Set<Triple<Int,String,Int>>>()

    fun add(newD : Set<Triple<Int,String,Int>>) {
        var toDelete = mutableSetOf<Set<Triple<Int,String,Int>>>()
        for (d in delta) {
            if (d.containsAll(newD)) {
                return
            }
            if (newD.containsAll(d)) {
                toDelete.add(d)
            }
        }
        delta.removeAll(toDelete)
        delta.add(newD)
    }

    operator fun plusAssign(newD : Set<Triple<Int,String,Int>>) {
        add(newD)
    }

    fun toSet() : Set<Set<Triple<Int,String,Int>>> {
        return delta
    }
}