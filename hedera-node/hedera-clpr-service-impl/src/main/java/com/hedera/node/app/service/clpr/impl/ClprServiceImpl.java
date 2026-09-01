// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CLPR_SERVICE_ADDRESS;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.DEFAULT_MAX_GAS_PER_MESSAGE;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.config.data.ClprConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link ClprService}.
 */
public final class ClprServiceImpl implements ClprService {

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0770ClprSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);

        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        final var defaultThrottles = ClprThrottles.newBuilder()
                .maxSyncBytes(4_194_304L) // 4 MB
                .maxMessagesPerBundle(1_000)
                .maxQueueDepth(10_000)
                .maxMessagePayloadBytes(65_536) // 64 KB per message
                .maxGasPerMessage(DEFAULT_MAX_GAS_PER_MESSAGE)
                .build();
        // Use seconds=1 as the genesis sentinel so timestamp is non-zero (distinguishable from
        // the proto default). Any admin UpdateLedgerConfiguration will overwrite this with the
        // actual consensus time of that transaction.
        final var genesisTimestamp = Timestamp.newBuilder().seconds(1L).build();
        // initial_trust_anchor / initial_trust_anchor_id are intentionally left unset at
        // genesis: the Hiero TSS ledger_id is only available once a signed block snapshot has
        // been produced, which has not happened by the time genesis setup runs. The first
        // ClprUpdateLedgerConfiguration after the ledger_id is available will populate them
        // from ClprStateProofManager.latestLedgerId(); until then the local config singleton
        // carries an empty trust anchor.
        final var initialConfig = ClprLedgerConfiguration.newBuilder()
                .chainId(clprConfig.chainId())
                .protocolVersion(clprConfig.protocolVersion())
                .serviceAddress(CLPR_SERVICE_ADDRESS)
                .timestamp(genesisTimestamp)
                .throttles(defaultThrottles)
                .build();
        writableStates
                .<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                .put(initialConfig);

        // Seed the endpoint manifest at version 1 with an empty endpoints list. Version >= 1
        // with no endpoints is a valid state per spec §2.4.1. Population from the consensus
        // roster on Hiero happens outside genesis setup (see #325).
        final var initialManifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .serviceAddress(CLPR_SERVICE_ADDRESS)
                .build();
        writableStates
                .<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                .put(initialManifest);
        return true;
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DataOneOfType.CLPR_UPDATE_LEDGER_CONFIGURATION),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REGISTER_CHANNEL, TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_CLOSE_CHANNEL, TransactionBody.DataOneOfType.CLPR_CLOSE_CHANNEL),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_COMPLETE_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_DEREGISTER_CONNECTOR),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_REDACT_MESSAGE, TransactionBody.DataOneOfType.CLPR_REDACT_MESSAGE),
                new ClprFeeCalculator(
                        HederaFunctionality.CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DataOneOfType.CLPR_ENDPOINT_PUBLICATION));
    }
}
