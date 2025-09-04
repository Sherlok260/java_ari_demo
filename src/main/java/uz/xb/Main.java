package uz.xb;

import ch.loway.oss.ari4java.ARI;
import ch.loway.oss.ari4java.AriVersion;
import ch.loway.oss.ari4java.generated.AriWSHelper;
import ch.loway.oss.ari4java.generated.models.AsteriskInfo;
import ch.loway.oss.ari4java.generated.models.Message;
import ch.loway.oss.ari4java.generated.models.StasisStart;
import ch.loway.oss.ari4java.generated.models.StasisEnd;
import ch.loway.oss.ari4java.generated.models.ChannelHangupRequest;
import ch.loway.oss.ari4java.tools.ARIException;
import ch.loway.oss.ari4java.tools.RestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    private int nextPort = 5000;  // start from 5000

    private synchronized int getNextPort() {
        return nextPort++;
    }


    private static final String ARI_APP = "xb-voicebot";
    private static final String AUDIOSOCKET_HOST = "127.0.0.1";
    private static final int AUDIOSOCKET_PORT = 5001;

    private ARI ari;
    private final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("** Expecting at least 3 arguments:\n  url user pass [ariversion]");
            System.exit(1);
        }
        AriVersion ver = AriVersion.ARI_9_0_0;
        if (args.length == 4) {
            ver = AriVersion.fromVersionString(args[3]);
        }
        new Main().start(args[0], args[1], args[2], ver);
    }

    private void start(String url, String user, String pass, AriVersion ver) {
        logger.info("THE START");
        boolean connected = connect(url, user, pass, ver);
        if (connected) {
            try {
                runEventLoop();
            } catch (Throwable t) {
                logger.error("Error: {}", t.getMessage(), t);
            } finally {
                logger.info("ARI cleanup");
                ari.cleanup();
            }
        }
        logger.info("THE END");
    }

    private boolean connect(String url, String user, String pass, AriVersion ver) {
        try {
            ari = ARI.build(url, ARI_APP, user, pass, ver);
            logger.info("ARI Version: {}", ari.getVersion().version());
            AsteriskInfo info = ari.asterisk().getInfo().execute();
            logger.info("AsteriskInfo up since {}", info.getStatus().getStartup_time());
            return true;
        } catch (Throwable t) {
            logger.error("Error: {}", t.getMessage(), t);
        }
        return false;
    }

    private void runEventLoop() throws InterruptedException, ARIException {
        final ExecutorService threadPool = Executors.newFixedThreadPool(10);

        ari.eventsCallback(new AriWSHelper() {
            @Override
            public void onSuccess(Message message) {
                threadPool.execute(() -> super.onSuccess(message));
            }

            @Override
            public void onFailure(RestException e) {
                logger.error("Error: {}", e.getMessage(), e);
                threadPool.shutdown();
            }

            @Override
            protected void onStasisStart(StasisStart message) {
                handleStart(message);
            }

            @Override
            protected void onStasisEnd(StasisEnd message) {
                logger.info("Call ended: {}", message.getChannel().getId());
            }

            @Override
            protected void onChannelHangupRequest(ChannelHangupRequest message) {
                logger.info("Hangup requested for channel: {}", message.getChannel().getId());
            }
        });

        // Run for 10 minutes (or until shutdown)
        threadPool.awaitTermination(10, TimeUnit.MINUTES);
        ari.cleanup();
        System.exit(0);
    }


    private void handleStart(StasisStart start) {
        String channelId = start.getChannel().getId();
        int port = getNextPort();  // allocate unique port
        String hostPort = "127.0.0.1:" + port;

        logger.info("New incoming call {} -> attaching ExternalMedia on {}", channelId, hostPort);

        try {
            ari.channels()
                    .externalMedia(Main.ARI_APP, hostPort, "slin16")
                    .execute();

            logger.info("ExternalMedia started for channel {} at {}", channelId, hostPort);

            // Spin up a listener socket to capture audio
            new Thread(() -> listenForAudio(port)).start();

        } catch (Throwable e) {
            logger.error("Error attaching ExternalMedia: {}", e.getMessage(), e);
        }
    }

    private void listenForAudio(int port) {
        try (ServerSocket server = new ServerSocket(port)) {
            Socket socket = server.accept();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[320]; // 20ms @ 16kHz 16-bit mono = 320 bytes
            while (true) {
                int read = in.read(buffer);
                if (read == -1) break;
                logger.info("Audio {} bytes: {}", read, java.util.Arrays.toString(buffer));
            }
        } catch (Exception e) {
            logger.error("Audio socket error: {}", e.getMessage(), e);
        }
    }


}
