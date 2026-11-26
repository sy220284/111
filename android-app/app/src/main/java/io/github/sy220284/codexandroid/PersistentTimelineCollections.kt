package io.github.sy220284.codexandroid

/** Immutable AVL index. Each write copies only the search path, retaining all
 * unaffected subtrees. No additional dependency or mutable shared collection. */
internal class PersistentIndex<K : Comparable<K>, V : Any> private constructor(private val root: Node<K, V>?) {
    constructor() : this(null)

    private class Node<K, V>(val key: K, val value: V, val left: Node<K, V>?, val right: Node<K, V>?) {
        val height: Int = 1 + maxOf(left?.height ?: 0, right?.height ?: 0)
    }

    operator fun get(key: K): V? {
        var cursor = root
        while (cursor != null) {
            val order = key.compareTo(cursor.key)
            if (order == 0) return cursor.value
            cursor = if (order < 0) cursor.left else cursor.right
        }
        return null
    }

    fun put(key: K, value: V): PersistentIndex<K, V> = PersistentIndex(insert(root, key, value))
    fun remove(key: K): PersistentIndex<K, V> = PersistentIndex(erase(root, key))

    private fun height(n: Node<K, V>?) = n?.height ?: 0
    private fun rotateLeft(n: Node<K, V>): Node<K, V> {
        val right = requireNotNull(n.right)
        return Node(right.key, right.value, Node(n.key, n.value, n.left, right.left), right.right)
    }
    private fun rotateRight(n: Node<K, V>): Node<K, V> {
        val left = requireNotNull(n.left)
        return Node(left.key, left.value, left.left, Node(n.key, n.value, left.right, n.right))
    }
    private fun balance(n: Node<K, V>): Node<K, V> {
        val diff = height(n.left) - height(n.right)
        if (diff > 1) {
            val left = requireNotNull(n.left)
            val adjusted = if (height(left.left) < height(left.right))
                Node(n.key, n.value, rotateLeft(left), n.right) else n
            return rotateRight(adjusted)
        }
        if (diff < -1) {
            val right = requireNotNull(n.right)
            val adjusted = if (height(right.right) < height(right.left))
                Node(n.key, n.value, n.left, rotateRight(right)) else n
            return rotateLeft(adjusted)
        }
        return n
    }
    private fun insert(n: Node<K, V>?, key: K, value: V): Node<K, V> {
        if (n == null) return Node(key, value, null, null)
        val order = key.compareTo(n.key)
        return when {
            order < 0 -> balance(Node(n.key, n.value, insert(n.left, key, value), n.right))
            order > 0 -> balance(Node(n.key, n.value, n.left, insert(n.right, key, value)))
            else -> if (n.value === value) n else Node(key, value, n.left, n.right)
        }
    }
    private fun erase(n: Node<K, V>?, key: K): Node<K, V>? {
        if (n == null) return null
        val order = key.compareTo(n.key)
        if (order < 0) return balance(Node(n.key, n.value, erase(n.left, key), n.right))
        if (order > 0) return balance(Node(n.key, n.value, n.left, erase(n.right, key)))
        if (n.left == null) return n.right
        if (n.right == null) return n.left
        var successor: Node<K, V> = n.right
        while (successor.left != null) successor = requireNotNull(successor.left)
        return balance(Node(successor.key, successor.value, n.left, erase(n.right, successor.key)))
    }

    /** Sequences use contiguous append-only keys, so equal-size trees have the
     * same shape. Equality skips unchanged branches by identity. */
    fun sameSequence(other: PersistentIndex<*, *>): Boolean {
        fun same(a: Node<*, *>?, b: Node<*, *>?): Boolean = a === b ||
            (a != null && b != null && a.key == b.key && a.value == b.value &&
                same(a.left, b.left) && same(a.right, b.right))
        return same(root, other.root)
    }
}

/** Indexed immutable sequence with logarithmic append/replace and shared branches.
 * The public List contract remains structural, including comparisons to plain lists. */
internal class PersistentSequence<T : Any> private constructor(
    private val index: PersistentIndex<Int, T>, override val size: Int
) : AbstractList<T>() {
    constructor() : this(PersistentIndex(), 0)
    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Sequence index $index")
        return requireNotNull(this.index[index])
    }
    fun append(value: T) = PersistentSequence(index.put(size, value), size + 1)
    fun replace(position: Int, value: T): PersistentSequence<T> {
        if (position !in 0 until size) throw IndexOutOfBoundsException("Sequence index $position")
        return PersistentSequence(index.put(position, value), size)
    }
    override fun equals(other: Any?): Boolean = this === other || if (other is PersistentSequence<*>)
        size == other.size && index.sameSequence(other.index) else super.equals(other)
    override fun hashCode(): Int = super.hashCode()
    companion object {
        fun <T : Any> from(items: Iterable<T>): PersistentSequence<T> {
            var result = PersistentSequence<T>()
            for (item in items) result = result.append(item)
            return result
        }
    }
}
