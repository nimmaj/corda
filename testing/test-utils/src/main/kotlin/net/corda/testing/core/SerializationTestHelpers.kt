package net.corda.testing.core

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.doNothing
import com.nhaarman.mockito_kotlin.whenever
import net.corda.client.rpc.internal.KryoClientSerializationScheme
import net.corda.core.DoNotImplement
import net.corda.core.internal.staticField
import net.corda.core.serialization.internal.SerializationEnvironment
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal._globalSerializationEnv
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.node.serialization.KryoServerSerializationScheme
import net.corda.nodeapi.internal.serialization.*
import net.corda.nodeapi.internal.serialization.amqp.AMQPClientSerializationScheme
import net.corda.nodeapi.internal.serialization.amqp.AMQPServerSerializationScheme
import net.corda.testing.common.internal.asContextEnv
import net.corda.testing.internal.rigorousMock
import net.corda.testing.internal.testThreadFactory
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnector
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val inVMExecutors = ConcurrentHashMap<SerializationEnvironment, ExecutorService>()

/** @param inheritable whether new threads inherit the environment, use sparingly. */
class SerializationEnvironmentRule(private val inheritable: Boolean = false) : TestRule {
    companion object {
        init {
            // Can't turn it off, and it creates threads that do serialization, so hack it:
            InVMConnector::class.staticField<ExecutorService>("threadPoolExecutor").value = rigorousMock<ExecutorService>().also {
                doAnswer {
                    inVMExecutors.computeIfAbsent(effectiveSerializationEnv) {
                        Executors.newCachedThreadPool(testThreadFactory(true)) // Close enough to what InVMConnector makes normally.
                    }.execute(it.arguments[0] as Runnable)
                }.whenever(it).execute(any())
            }
        }

        /** Do not call, instead use [SerializationEnvironmentRule] as a [org.junit.Rule]. */
        fun <T> run(taskLabel: String, task: (SerializationEnvironment) -> T): T {
            return SerializationEnvironmentRule().apply { init(taskLabel) }.runTask(task)
        }
    }

    private lateinit var env: SerializationEnvironment
    val serializationFactory get() = env.serializationFactory
    val checkpointContext get() = env.checkpointContext

    override fun apply(base: Statement, description: Description): Statement {
        init(description.toString())
        return object : Statement() {
            override fun evaluate() = runTask { base.evaluate() }
        }
    }

    private fun init(envLabel: String) {
        env = createTestSerializationEnv(envLabel)
    }

    private fun <T> runTask(task: (SerializationEnvironment) -> T): T {
        try {
            return env.asContextEnv(inheritable, task)
        } finally {
            inVMExecutors.remove(env)
        }
    }
}

@DoNotImplement
interface GlobalSerializationEnvironment : SerializationEnvironment {
    /** Unset this environment. */
    fun unset()
}

/**
 * Should only be used by Driver and MockNode.
 * @param armed true to install, false to do nothing and return a dummy env.
 */
fun setGlobalSerialization(armed: Boolean): GlobalSerializationEnvironment {
    return if (armed) {
        object : GlobalSerializationEnvironment, SerializationEnvironment by createTestSerializationEnv("<global>") {
            override fun unset() {
                _globalSerializationEnv.set(null)
                inVMExecutors.remove(this)
            }
        }.also {
            _globalSerializationEnv.set(it)
        }
    } else {
        rigorousMock<GlobalSerializationEnvironment>().also {
            doNothing().whenever(it).unset()
        }
    }
}

private fun createTestSerializationEnv(label: String): SerializationEnvironmentImpl {
    val factory = SerializationFactoryImpl().apply {
        registerScheme(KryoClientSerializationScheme())
        registerScheme(KryoServerSerializationScheme())
        registerScheme(AMQPClientSerializationScheme(emptyList()))
        registerScheme(AMQPServerSerializationScheme(emptyList()))
    }
    return object : SerializationEnvironmentImpl(
            factory,
            AMQP_P2P_CONTEXT,
            KRYO_RPC_SERVER_CONTEXT,
            KRYO_RPC_CLIENT_CONTEXT,
            AMQP_STORAGE_CONTEXT,
            KRYO_CHECKPOINT_CONTEXT
    ) {
        override fun toString() = "testSerializationEnv($label)"
    }
}
