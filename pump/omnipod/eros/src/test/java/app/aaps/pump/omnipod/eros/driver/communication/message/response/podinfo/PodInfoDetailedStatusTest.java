package app.aaps.pump.omnipod.eros.driver.communication.message.response.podinfo;

import static com.google.common.truth.Truth.assertThat;

import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

import app.aaps.core.utils.pump.ByteUtil;
import app.aaps.pump.omnipod.eros.driver.definition.DeliveryStatus;
import app.aaps.pump.omnipod.eros.driver.definition.ErrorEventInfo;
import app.aaps.pump.omnipod.eros.driver.definition.FaultEventCode;
import app.aaps.pump.omnipod.eros.driver.definition.PodProgressStatus;

/**
 * @noinspection SpellCheckingInspection
 */ // From https://github.com/ps2/rileylink_ios/blob/omnipod-testing/OmniKitTests/PodInfoTests.swift
class PodInfoDetailedStatusTest {
    @Test
    void testPodInfoFaultEventNoFaultAlerts() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("02080100000a003800000003ff008700000095ff0000"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.NORMAL);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(0);
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x0a);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isNull();
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isEqualTo(Duration.ZERO);
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getTimeActive()).isEqualTo(Duration.standardSeconds(8100));
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo).isNull();
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isNull();
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(2);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(21);
    }

    @Test
    void testPodInfoFaultEventDeliveryErrorDuringPriming() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("020f0000000900345c000103ff0001000005ae056029"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.INACTIVE);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.SUSPENDED);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(0);
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x09);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isEqualTo(FaultEventCode.PRIME_OPEN_COUNT_TOO_LOW);
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isEqualTo(Duration.standardSeconds(60));
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getTimeActive()).isEqualTo(Duration.standardSeconds(60));
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo.isInsulinStateTableCorruption()).isFalse();
        assertThat(errorEventInfo.getInternalVariable()).isEqualTo(0x00);
        assertThat(errorEventInfo.isImmediateBolusInProgress()).isFalse();
        assertThat(errorEventInfo.getPodProgressStatus()).isEqualTo(PodProgressStatus.PRIMING_COMPLETED);
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isEqualTo(PodProgressStatus.PRIMING_COMPLETED);
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(2);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(46);
    }

    @Test
    void testPodInfoFaultEventErrorShuttingDown() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("020d0000000407f28609ff03ff0a0200000823080000"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.FAULT_EVENT_OCCURRED);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.SUSPENDED);
        assertThat(podInfoDetailedStatus.getTicksDelivered()).isEqualTo(2034);
        assertThat(podInfoDetailedStatus.getInsulinDelivered()).isWithin(0.000001).of(101.7);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(0);
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x04);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isEqualTo(FaultEventCode.BASAL_OVER_INFUSION_PULSE);
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isEqualTo(Duration.standardMinutes(2559));
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo.isInsulinStateTableCorruption()).isFalse();
        assertThat(errorEventInfo.getInternalVariable()).isEqualTo(0x00);
        assertThat(errorEventInfo.isImmediateBolusInProgress()).isFalse();
        assertThat(errorEventInfo.getPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(35);
    }

    @Test
    void testPodInfoFaultEventInsulinNotDelivered() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("020f0000010200ec6a026803ff026b000028a7082023"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.INACTIVE);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.SUSPENDED);
        assertThat(podInfoDetailedStatus.getTicksDelivered()).isEqualTo(236);
        assertThat(podInfoDetailedStatus.getInsulinDelivered()).isWithin(0.000001).of(11.8);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(0.05);
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x02);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isEqualTo(FaultEventCode.OCCLUSION_CHECK_ABOVE_THRESHOLD);
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isEqualTo(Duration.standardMinutes(616));
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo.isInsulinStateTableCorruption()).isFalse();
        assertThat(errorEventInfo.getInternalVariable()).isEqualTo(0x01);
        assertThat(errorEventInfo.isImmediateBolusInProgress()).isFalse();
        assertThat(errorEventInfo.getPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(2);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(39);
    }

    @Test
    void testPodInfoFaultEventMaxBolusNotDelivered() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("020f00ffff0200ec6a026803ff026b000028a7082023"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.INACTIVE);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.SUSPENDED);
        assertThat(podInfoDetailedStatus.getTicksDelivered()).isEqualTo(236);
        assertThat(podInfoDetailedStatus.getInsulinDelivered()).isWithin(0.000001).of(11.8);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(3276.75); // Insane and will not happen, but this verifies that we convert it to an unsigned int
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x02);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isEqualTo(FaultEventCode.OCCLUSION_CHECK_ABOVE_THRESHOLD);
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isEqualTo(Duration.standardMinutes(616));
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo.isInsulinStateTableCorruption()).isFalse();
        assertThat(errorEventInfo.getInternalVariable()).isEqualTo(0x01);
        assertThat(errorEventInfo.isImmediateBolusInProgress()).isFalse();
        assertThat(errorEventInfo.getPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isEqualTo(PodProgressStatus.ABOVE_FIFTY_UNITS);
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(2);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(39);
    }

    @Test
    void testPodInfoFaultEventInsulinStateTableCorruptionFoundDuringErrorLogging() {
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("020D00000000000012FFFF03FF00160000879A070000"));

        assertThat(podInfoDetailedStatus.getPodProgressStatus()).isEqualTo(PodProgressStatus.FAULT_EVENT_OCCURRED);
        assertThat(podInfoDetailedStatus.getDeliveryStatus()).isEqualTo(DeliveryStatus.SUSPENDED);
        assertThat(podInfoDetailedStatus.getBolusNotDelivered()).isWithin(0.000001).of(0);
        assertThat(podInfoDetailedStatus.getPodMessageCounter()).isEqualTo(0x00);
        assertThat(podInfoDetailedStatus.getFaultEventCode()).isEqualTo(FaultEventCode.RESET_DUE_TO_LVD);
        assertThat(podInfoDetailedStatus.getFaultEventTime()).isNull();
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNull();
        assertThat(podInfoDetailedStatus.getTimeActive()).isEqualTo(Duration.standardSeconds(1320));
        assertThat(podInfoDetailedStatus.getUnacknowledgedAlerts().getRawValue()).isEqualTo(0);
        assertThat(podInfoDetailedStatus.isFaultAccessingTables()).isFalse();
        ErrorEventInfo errorEventInfo = podInfoDetailedStatus.getErrorEventInfo();
        assertThat(errorEventInfo.isInsulinStateTableCorruption()).isTrue();
        assertThat(errorEventInfo.getInternalVariable()).isEqualTo(0x00);
        assertThat(errorEventInfo.isImmediateBolusInProgress()).isFalse();
        assertThat(errorEventInfo.getPodProgressStatus()).isEqualTo(PodProgressStatus.INSERTING_CANNULA);
        assertThat(podInfoDetailedStatus.getPreviousPodProgressStatus()).isEqualTo(PodProgressStatus.INSERTING_CANNULA);
        assertThat(podInfoDetailedStatus.getReceiverLowGain()).isEqualTo(2);
        assertThat(podInfoDetailedStatus.getRadioRSSI()).isEqualTo(26);
    }

    @Test
    void testReservoirLevelCombinesHighAndLowByteBeforeScaling() {
        // encodedData[11] low 2 bits = 1 -> high tick contribution = 1 << 8 = 256
        // encodedData[12] = 0x2c (44) -> low tick contribution = 44
        // combined ticks = 300 -> 300 * POD_PULSE_SIZE (0.05) = 15.0 U.
        // A precedence bug that multiplied only the low byte before adding the high-bit term
        // (256 + 44*0.05 = 258.2) would push this well past MAX_RESERVOIR_READING (50) and
        // wrongly report the reservoir level as unknown (null) instead of 15.0.
        PodInfoDetailedStatus podInfoDetailedStatus = new PodInfoDetailedStatus(ByteUtil.INSTANCE.fromHexString("02080100000a0038000000012c008700000095ff0000"));

        assertThat(podInfoDetailedStatus.getReservoirLevel()).isNotNull();
        assertThat(podInfoDetailedStatus.getReservoirLevel()).isWithin(0.000001).of(15.0);
    }
}
