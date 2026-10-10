package io.github.v2compose.singbox

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator

/** libbox 的 StringIterator 实现 */
class SingBoxStringArray(private val values: List<String>) : StringIterator {

    private val iterator = values.iterator()

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): String = iterator.next()

    override fun len(): Int = values.size
}

/** libbox 的 NetworkInterfaceIterator 实现 */
class SingBoxInterfaceArray(private val values: List<NetworkInterface>) : NetworkInterfaceIterator {

    private val iterator = values.iterator()

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): NetworkInterface = iterator.next()
}
