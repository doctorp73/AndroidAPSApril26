package app.aaps.pump.omnipod.common.bledriver.comm.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.toHex
import app.aaps.pump.omnipod.common.bledriver.comm.Ids
import app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.CouldNotParseResponseException
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessagePacket
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageSendErrorConfirming
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageSendErrorSending
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageSendSuccess
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageType
import app.aaps.pump.omnipod.common.bledriver.comm.message.StringLengthPrefixEncoding
import app.aaps.pump.omnipod.common.bledriver.comm.message.StringLengthPrefixEncoding.Companion.parseKeys
import app.aaps.pump.omnipod.common.bledriver.pod.command.base.Command
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.NakResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.Response

sealed class CommandSendResult
object CommandSendSuccess : CommandSendResult()
data class CommandSendErrorSending(val msg: String) : CommandSendResult()

// This error marks the undefined state
data class CommandSendErrorConfirming(val msg: String) : CommandSendResult()

sealed class CommandReceiveResult
data class CommandReceiveSuccess(val result: Response) : CommandReceiveResult()
data class CommandReceiveError(val msg: String) : CommandReceiveResult()
data class CommandAckError(val result: Response, val msg: String) : CommandReceiveResult()

class Session(
    private val aapsLogger: AAPSLogger,
    private val msgIO: MessageIO,
    private val ids: Ids,
    val sessionKeys: SessionKeys,
    val enDecrypt: EnDecrypt
) {

    fun sendCommand(cmd: Command): CommandSendResult {
        sessionKeys.msgSequenceNumber++
        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command: ${cmd.encoded.toHex()} in packet $cmd")

        val msg = getCmdMessage(cmd)
        for (i in 0..MAX_TRIES) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command(wrapped): ${msg.payload.toHex()}")

            when (val sendResult = msgIO.sendMessage(msg)) {
                is MessageSendSuccess         ->
                    return CommandSendSuccess

                is MessageSendErrorConfirming -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Error confirming command: $sendResult")
                    return CommandSendErrorConfirming(sendResult.msg)
                }

                is MessageSendErrorSending    ->
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Error sending command: $sendResult")
            }
        }

        val errMsg = "Maximum number of tries reached. Could not send command"
        return CommandSendErrorSending(errMsg)
    }

    @Suppress("ReturnCount")
    fun readAndAckResponse(): CommandReceiveResult {
        var responseMsgPacket: MessagePacket? = null
        for (i in 0..MAX_TRIES) {
            val responseMsg = msgIO.receiveMessage()
            if (responseMsg != null) {
                responseMsgPacket = responseMsg
                break
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "Error receiving response: $responseMsg")
        }

        responseMsgPacket
            ?: return CommandReceiveError("Could not read response")

        val decrypted = enDecrypt.decrypt(responseMsgPacket)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Received response: $decrypted")

        val response = parseResponse(decrypted)

        sessionKeys.msgSequenceNumber++
        val ack = getAck(responseMsgPacket)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending ACK: ${ack.payload.toHex()} in packet $ack")
        val sendResult = msgIO.sendMessage(ack)
        if (sendResult !is MessageSendSuccess) {
            return CommandAckError(response, "Could not ACK the response: $sendResult")
        }
        // A NAK or alarm-status response is a well-formed, successfully-decoded reply, but it means
        // the pod rejected the command or is in a fault state - not that the command succeeded. Every
        // call site downstream (O5PumpPlugin's bolus/TBR/basal-program/deactivate commands) discards
        // the actual Response object via .ignoreElements().blockingAwait() and only distinguishes
        // success from failure by whether that Completable throws, so without this check a
        // pod-rejected command silently completed as if it had been accepted. Still ACK it above (the
        // pod is waiting for acknowledgement of receipt regardless of what it sent) - only the
        // reported outcome changes here.
        if (response is NakResponse || response is AlarmStatusResponse) {
            return CommandReceiveError("Pod rejected command or reported a fault: $response")
        }
        return CommandReceiveSuccess(response)
    }

    @Throws(CouldNotParseResponseException::class, UnsupportedOperationException::class)
    private fun parseResponse(decrypted: MessagePacket): Response {

        val data = parseKeys(arrayOf(RESPONSE_PREFIX), decrypted.payload)[0]
        aapsLogger.info(LTag.PUMPBTCOMM, "Received decrypted response: ${data.toHex()} in packet: $decrypted")

        // Left deliberately non-enforcing: no reference implementation of this specific
        // envelope's uniqueId/sequenceNumber/CRC check was found in OmnipodKit (its own
        // PodCommsSession.swift doesn't appear to validate this text-wrapped "0.0=..." framing
        // either), and this envelope's CRC algorithm hasn't been confirmed to match any of the
        // CRC variants already in this codebase (MessageUtil.createCrc, crc16XMODEM). Adding
        // enforcement here on an unconfirmed guess - in a code path that has never run against
        // real hardware - risks rejecting genuinely valid responses, which is worse than the
        // current permissive behavior. Logged instead, so a real mismatch is at least visible.
        if (data.size < RESPONSE_ENVELOPE_MIN_SIZE) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Response envelope shorter than expected (${data.size} bytes): ${data.toHex()}")
        } else {
            val uniqueId = data.copyOfRange(0, 4)
            val lengthAndSequenceNumber = data.copyOfRange(4, 6)
            val crc = data.copyOfRange(data.size - 2, data.size)
            aapsLogger.debug(
                LTag.PUMPBTCOMM,
                "Response envelope fields: uniqueId=${uniqueId.toHex()}, lengthAndSequenceNumber=${lengthAndSequenceNumber.toHex()}, crc=${crc.toHex()}"
            )
        }
        val payload = data.copyOfRange(6, data.size - 2)

        return ResponseUtil.parseResponse(payload)
    }

    private fun getAck(response: MessagePacket): MessagePacket {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            sequenceNumber = sessionKeys.msgSequenceNumber,
            source = ids.myId,
            destination = ids.podId,
            payload = ByteArray(0),
            eqos = 0,
            ack = true,
            ackNumber = response.sequenceNumber.inc()
        )
        return enDecrypt.encrypt((msg))
    }

    private fun getCmdMessage(cmd: Command): MessagePacket {
        val wrapped = StringLengthPrefixEncoding.formatKeys(
            arrayOf(COMMAND_PREFIX, COMMAND_SUFFIX),
            arrayOf(cmd.encoded, ByteArray(0))
        )

        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command: ${wrapped.toHex()}")

        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            sequenceNumber = sessionKeys.msgSequenceNumber,
            source = ids.myId,
            destination = ids.podId,
            payload = wrapped,
            eqos = 1
        )

        return enDecrypt.encrypt(msg)
    }

    companion object {

        private const val COMMAND_PREFIX = "S0.0="
        private const val COMMAND_SUFFIX = ",G0.0"
        private const val RESPONSE_PREFIX = "0.0="

        /** 4-byte uniqueId + 2-byte length/sequence + 2-byte CRC surrounding the payload. */
        private const val RESPONSE_ENVELOPE_MIN_SIZE = 8

        private const val MAX_TRIES = 4
    }
}
