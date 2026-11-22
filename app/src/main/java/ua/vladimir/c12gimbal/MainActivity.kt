        }.start()
    }

    private fun startCameraControl() {
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(cameraPort)
                socket.soTimeout = 100

                runOnUiThread {
                    udpStatus.text = "●  UDP C12: локальний порт 5000 відкритий; камера $cameraIp:5000"
                    udpStatus.setTextColor(accentColor)
                }

                var lastPitchSpeed = 0
                var lastZoomTime = 0L
                var centerPressed = false
                var downPressed = false
                var lastStaleStop = false

                while (running) {
                    val now = System.currentTimeMillis()
                    val channels = latestChannels
                    val rcFresh = now - lastRcPacketMs < 500 && lastRcPacketMs > 0

                    if (!rcFresh) {
                        if (lastPitchSpeed != 0) {
                            sendPitchSpeed(socket, 0)
                            lastPitchSpeed = 0
                        }
                        if (!lastStaleStop) {
                            runOnUiThread {
                                udpStatus.text = "●  UDP C12: очікування сигналу пульта — рух зупинено"
                                udpStatus.setTextColor(warningColor)
                            }
                            lastStaleStop = true
                        }
                        Thread.sleep(100)
                        continue
                    }
                    lastStaleStop = false

                    // CH6 PITCH: швидкість збільшена вдвічі.
                    // Було ±20, стало ±40; за шкалою GSP це приблизно до 4°/с.
                    val pitchDifference = channels[5] - 1500
                    val pitchSpeed = if (abs(pitchDifference) <= 30) {
                        0
                    } else {
                        (pitchDifference * 40 / 450).coerceIn(-40, 40)
                    }

                    if (pitchSpeed != 0 || lastPitchSpeed != 0) {
                        sendPitchSpeed(socket, pitchSpeed)
                        lastPitchSpeed = pitchSpeed
                    }

                    // CH7 ZOOM: інтервал між кроками зменшений удвічі: 350 -> 175 мс.
                    val zoomPwm = channels[6]
                    if (now - lastZoomTime >= 175) {
                        when {
                            zoomPwm > 1600 -> {
                                sendZoomIn(socket)
                                lastZoomTime = now
                            }
                            zoomPwm < 1400 -> {
                                sendZoomOut(socket)
                                lastZoomTime = now
                            }
                        }
                    }

                    val centerNow = channels[11] > 1700
                    if (centerNow && !centerPressed) {
                        sendPitchSpeed(socket, 0)
                        lastPitchSpeed = 0
                        sendCenter(socket)
                        runOnUiThread {
                            udpStatus.text = "●  UDP C12: команда центрування CH12 надіслана"
                            udpStatus.setTextColor(accentColor)
                        }
                    }
                    centerPressed = centerNow

                    val downNow = channels[12] > 1700
                    if (downNow && !downPressed) {
                        sendPitchSpeed(socket, 0)
                        lastPitchSpeed = 0
                        sendDown(socket)
                        runOnUiThread {
                            udpStatus.text = "●  UDP C12: команда нахилу вниз CH13 надіслана"
                            udpStatus.setTextColor(accentColor)
                        }
                    }
                    downPressed = downNow
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                if (running) {
                    runOnUiThread {
                        udpStatus.text = "●  ПОМИЛКА UDP: ${e.javaClass.simpleName} — ${e.message}"
                        udpStatus.setTextColor(warningColor)
                    }
                }
            } finally {
                try {
                    socket?.let {
                        if (!it.isClosed) {
                            sendPitchSpeed(it, 0)
                            it.close()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
