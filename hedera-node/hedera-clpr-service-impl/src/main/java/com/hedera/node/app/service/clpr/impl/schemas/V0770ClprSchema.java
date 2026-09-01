// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Genesis schema for the CLPR service.
 */
public class V0770ClprSchema extends Schema<SemanticVersion> {

    /** Channels state ID */
    public static final int CHANNELS_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_CHANNELS.protoOrdinal();

    /** Channels state key */
    public static final String CHANNELS_KEY = "CHANNELS";

    /** Pending commitments state ID */
    public static final int PENDING_COMMITMENTS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_PENDING_COMMITMENTS.protoOrdinal();

    /** Pending commitments state key */
    public static final String PENDING_COMMITMENTS_KEY = "PENDING_COMMITMENTS";

    /** Ledger configuration state ID */
    public static final int LEDGER_CONFIGURATION_STATE_ID =
            SingletonType.CLPRSERVICE_I_LEDGER_CONFIGURATION.protoOrdinal();

    /** Message queue state ID */
    public static final int MESSAGE_QUEUE_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_MESSAGE_QUEUE.protoOrdinal();

    /** Message queue state key */
    public static final String MESSAGE_QUEUE_KEY = "MESSAGE_QUEUE";

    /** Connectors state ID */
    public static final int CONNECTORS_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_CONNECTORS.protoOrdinal();

    /** Connectors state key */
    public static final String CONNECTORS_KEY = "CONNECTORS";

    /** Pending connector commitments state ID */
    public static final int PENDING_CONNECTOR_COMMITMENTS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_PENDING_CONNECTOR_COMMITMENTS.protoOrdinal();

    /** Pending connector commitments state key */
    public static final String PENDING_CONNECTOR_COMMITMENTS_KEY = "PENDING_CONNECTOR_COMMITMENTS";

    /** Ledger configuration singleton state key */
    public static final String LEDGER_CONFIGURATION_KEY = "LEDGER_CONFIGURATION";

    /** Endpoint manifest singleton state ID */
    public static final int ENDPOINT_MANIFEST_STATE_ID = SingletonType.CLPRSERVICE_I_ENDPOINT_MANIFEST.protoOrdinal();

    /** Endpoint manifest singleton state key */
    public static final String ENDPOINT_MANIFEST_KEY = "ENDPOINT_MANIFEST";

    /** Endpoint manifest construction singleton state ID */
    public static final int ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID =
            SingletonType.CLPRSERVICE_I_ENDPOINT_MANIFEST_CONSTRUCTION.protoOrdinal();

    /** Endpoint manifest construction singleton state key */
    public static final String ENDPOINT_MANIFEST_CONSTRUCTION_KEY = "ENDPOINT_MANIFEST_CONSTRUCTION";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(65).patch(0).build();

    /** EVM address of the Hiero CLPR service contract: 0x000000000000000000000000000000000000016e */
    public static final Bytes CLPR_SERVICE_ADDRESS = CLPR_EVM_ADDRESS_BYTES;

    /** Default max gas per inbound message dispatch (15M, matching the consensus gas budget). */
    public static final long DEFAULT_MAX_GAS_PER_MESSAGE = 15_000_000L;

    /**
     * Constructor for this schema.
     */
    public V0770ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.keyValue(CHANNELS_STATE_ID, CHANNELS_KEY, ProtoBytes.PROTOBUF, ClprChannel.PROTOBUF),
                StateDefinition.keyValue(
                        PENDING_COMMITMENTS_STATE_ID,
                        PENDING_COMMITMENTS_KEY,
                        ProtoBytes.PROTOBUF,
                        ProtoBytes.PROTOBUF),
                StateDefinition.keyValue(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID,
                        PENDING_CONNECTOR_COMMITMENTS_KEY,
                        ProtoBytes.PROTOBUF,
                        ProtoBytes.PROTOBUF),
                StateDefinition.keyValue(
                        MESSAGE_QUEUE_STATE_ID, MESSAGE_QUEUE_KEY, ClprMessageKey.PROTOBUF, ClprMessageValue.PROTOBUF),
                StateDefinition.keyValue(
                        CONNECTORS_STATE_ID, CONNECTORS_KEY, ClprConnectorKey.PROTOBUF, ClprConnector.PROTOBUF),
                StateDefinition.singleton(
                        LEDGER_CONFIGURATION_STATE_ID, LEDGER_CONFIGURATION_KEY, ClprLedgerConfiguration.PROTOBUF),
                StateDefinition.singleton(
                        ENDPOINT_MANIFEST_STATE_ID, ENDPOINT_MANIFEST_KEY, ClprEndpointManifest.PROTOBUF),
                StateDefinition.singleton(
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                        ENDPOINT_MANIFEST_CONSTRUCTION_KEY,
                        ClprEndpointManifestConstruction.PROTOBUF));
    }
}
