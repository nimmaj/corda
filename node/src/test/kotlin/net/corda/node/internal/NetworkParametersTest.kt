package net.corda.node.internal

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.services.config.NotaryConfig
import net.corda.nodeapi.internal.network.NetworkParameters
import net.corda.nodeapi.internal.network.NetworkParametersCopier
import net.corda.nodeapi.internal.network.NotaryInfo
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.chooseIdentity
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.node.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertFails

class NetworkParametersTest {
    private val mockNet = MockNetwork(
            emptyList(),
            MockNetworkParameters(networkSendManuallyPumped = true),
            notarySpecs = listOf(MockNetwork.NotarySpec(DUMMY_NOTARY_NAME)))

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

    // Minimum Platform Version tests
    @Test
    fun `node shutdowns when on lower platform version than network`() {
        val alice = mockNet.createUnstartedNode(MockNodeParameters(legalName = ALICE_NAME, forcedID = 100, version = MockServices.MOCK_VERSION_INFO.copy(platformVersion = 1)))
        val aliceDirectory = mockNet.baseDirectory(100)
        val netParams = testNetworkParameters(
                notaries = listOf(NotaryInfo(mockNet.defaultNotaryIdentity, true)),
                minimumPlatformVersion = 2)
        dropParametersToDir(aliceDirectory, netParams)
        assertThatThrownBy { alice.start() }.hasMessageContaining("platform version")
    }

    @Test
    fun `node works fine when on higher platform version`() {
        val alice = mockNet.createUnstartedNode(MockNodeParameters(legalName = ALICE_NAME, forcedID = 100, version = MockServices.MOCK_VERSION_INFO.copy(platformVersion = 2)))
        val aliceDirectory = mockNet.baseDirectory(100)
        val netParams = testNetworkParameters(
                notaries = listOf(NotaryInfo(mockNet.defaultNotaryIdentity, true)),
                minimumPlatformVersion = 1)
        dropParametersToDir(aliceDirectory, netParams)
        alice.start()
    }

    // Notaries tests
    @Test
    fun `choosing notary not specified in network parameters will fail`() {
        val fakeNotary = mockNet.createNode(MockNodeParameters(legalName = BOB_NAME, configOverrides = {
            val notary = NotaryConfig(false)
            doReturn(notary).whenever(it).notary}))
        val fakeNotaryId = fakeNotary.info.chooseIdentity()
        val alice = mockNet.createPartyNode(ALICE_NAME)
        assertThat(alice.services.networkMapCache.notaryIdentities).doesNotContain(fakeNotaryId)
        assertFails {
            alice.services.startFlow(CashIssueFlow(500.DOLLARS, OpaqueBytes.of(0x01), fakeNotaryId)).getOrThrow()
        }
    }

    // Helpers
    private fun dropParametersToDir(dir: Path, params: NetworkParameters) {
        NetworkParametersCopier(params).install(dir)
    }
}