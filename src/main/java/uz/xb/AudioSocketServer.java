package uz.xb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class AudioSocketServer {

    private static final int PORT = 5000;

    public static void main(String[] args) {
        new AudioSocketServer().start();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("AudioSocket server started on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New AudioSocket connection: " + socket.getRemoteSocketAddress());

                new Thread(() -> handleAudioSocket(socket)).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleAudioSocket(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            // 50ms = 1600 bytes @16kHz
            byte[] buffer = new byte[1600];

            while (true) {
                int read = in.read(buffer);
                if (read < 0) break; // call tugadi

                if (read > 0) {
                    // Asterisk → Siz (caller audio)
                    sendToVadGrpc(buffer, read);

                    // Siz → Asterisk (masalan playback yoki TTS)
//                    byte[] playbackChunk = getFromTtsOrFile(read);
                    byte[] playbackChunk = buffer;
                    if (playbackChunk != null) {
                        out.write(playbackChunk);
                        out.flush();
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void sendToVadGrpc(byte[] audioChunk, int length) {
        // TODO: bu yerga sizning gRPC VAD client kodini yozasiz
        // Masalan:
        // vadStub.streamAudio(
        //      AudioRequest.newBuilder()
        //          .setAudio(ByteString.copyFrom(audioChunk, 0, length))
        //          .build()
        // );
        System.out.println("Sending " + length + " bytes to VAD gRPC...");
    }
}
