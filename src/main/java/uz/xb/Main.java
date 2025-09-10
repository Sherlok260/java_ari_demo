package uz.xb;

import ch.loway.oss.ari4java.ARI;
import ch.loway.oss.ari4java.AriVersion;
import ch.loway.oss.ari4java.generated.AriWSHelper;
import ch.loway.oss.ari4java.generated.models.*;
import ch.loway.oss.ari4java.tools.ARIException;
import ch.loway.oss.ari4java.tools.RestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final String ARI_APP = "xb-voicebot";

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
                weasels();
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

    private void weasels() throws InterruptedException, ARIException {
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
            protected void onPlaybackFinished(PlaybackFinished message) {
                handlePlaybackFinished(message);
            }

            @Override
            protected void onChannelVarset(ChannelVarset message) {
                handleChannelVarset(message);
            }
        });
        // usually we would not terminate and run indefinitely
        // waiting for 5 minutes before shutting down...
        threadPool.awaitTermination(5, TimeUnit.MINUTES);
        ari.cleanup();
        System.exit(0);
    }

    private void handleChannelVarset(ChannelVarset message) {
        String channelId = message.getChannel().getId();
        String variable = message.getVariable();
        String value = message.getValue();

        logger.info("ChannelVarset - Channel: {}, Var: {}, Value: {}",
                channelId, variable, value);

        if ("TALK_DETECTED".equalsIgnoreCase(variable) && "yes".equalsIgnoreCase(value)) {
            logger.info("Human started speaking on channel {}", channelId);
            // TODO: start buffering audio for STT
        }

        if ("SILENCE_DETECTED".equalsIgnoreCase(variable) && "yes".equalsIgnoreCase(value)) {
            logger.info("Silence detected on channel {}", channelId);
            // TODO: stop buffering, send segment to STT -> LLM -> TTS pipeline
        }
    }


    private void handleStart(StasisStart start) {
        String channelId = start.getChannel().getId();
        logger.info("Stasis Start Channel: {}", channelId);

        try {
            // Yangi AudioSocket kanal yaratish
            String endpoint = "audiosocket:stream"; // audiosocket.conf dagi section nomi
            Channel audioChan = ari.channels()
                    .create(endpoint, ARI_APP)
                    .execute();

            logger.info("AudioSocket channel created: {}", audioChan.getId());

            // Bridge yaratish
            Bridge bridge = ari.bridges()
                    .create()
                    .setType("mixing")
                    .execute();

            logger.info("Bridge created: {}", bridge.getId());

            // Call channel va AudioSocket channelni bridgega qo'shish
            ari.bridges().addChannel(bridge.getId(), channelId).execute();
            ari.bridges().addChannel(bridge.getId(), audioChan.getId()).execute();

            logger.info("Both channels added to bridge {}", bridge.getId());

        } catch (RestException e) {
            logger.error("Failed to create AudioSocket channel: {}", e.getMessage(), e);
        }
    }






    private void handlePlaybackFinished(PlaybackFinished playback) {
        logger.info("PlaybackFinished - {}", playback.getPlayback().getTarget_uri());
        if (playback.getPlayback().getTarget_uri().indexOf("channel:") == 0) {
            try {
                String chanId = playback.getPlayback().getTarget_uri().split(":")[1];
                logger.info("Hangup Channel: {}", chanId);
                ARI.sleep(300); // a slight pause before we hangup ...
                ari.channels().hangup(chanId).execute();
            } catch (Throwable e) {
                logger.error("Error: {}", e.getMessage(), e);
            }
        } else {
            logger.error("Cannot handle URI - {}", playback.getPlayback().getTarget_uri());
        }
    }

}
