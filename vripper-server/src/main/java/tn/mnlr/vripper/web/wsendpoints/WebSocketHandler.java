package tn.mnlr.vripper.web.wsendpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.disposables.Disposable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tn.mnlr.vripper.entities.Image;
import tn.mnlr.vripper.entities.Post;
import tn.mnlr.vripper.entities.mixin.ui.ImageUIMixin;
import tn.mnlr.vripper.entities.mixin.ui.PostUIMixin;
import tn.mnlr.vripper.services.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    private final GlobalStateService globalStateService;
    private final AppStateExchange appStateExchange;
    private final DownloadSpeedService downloadSpeedService;
    private final VipergirlsAuthService vipergirlsAuthService;
    private final CommonExecutor commonExecutor;
    private final Map<String, Disposable> postsSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> postDetailsSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> stateSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> downloadSpeedSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> userSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> grabQueueSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Future<Void>> threadParseRequests = new ConcurrentHashMap<>();

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    public WebSocketHandler(GlobalStateService globalStateService, AppStateExchange appStateExchange, DownloadSpeedService downloadSpeedService, VipergirlsAuthService vipergirlsAuthService, CommonExecutor commonExecutor) {
        this.globalStateService = globalStateService;
        this.appStateExchange = appStateExchange;
        this.downloadSpeedService = downloadSpeedService;
        this.vipergirlsAuthService = vipergirlsAuthService;
        this.commonExecutor = commonExecutor;

        om.addMixIn(Image.class, ImageUIMixin.class).addMixIn(Post.class, PostUIMixin.class);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        WSMessage wsMessage = om.readValue(message.getPayload(), WSMessage.class);
        WSMessage.CMD cmd = WSMessage.CMD.valueOf(wsMessage.getCmd());
        switch (cmd) {
            case GRAB_QUEUE_SUB:
                subscribeForGrabQueue(session);
                break;
            case GLOBAL_STATE_SUB:
                subscribeForGlobalState(session);
                break;
            case SPEED_SUB:
                subscribeForSpeed(session);
                break;
            case USER_SUB:
                subscribeForUser(session);
                break;
            case POSTS_SUB:
                subscribeForPosts(session);
                break;
            case POST_DETAILS_SUB:
                subscribeForPostDetails(session, wsMessage.getPayload());
                break;
            case POST_DETAILS_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from post details", session.getId()));
                Optional.ofNullable(postDetailsSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
            case POSTS_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from posts", session.getId()));
                Optional.ofNullable(postsSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
            case GLOBAL_STATE_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from global state", session.getId()));
                Optional.ofNullable(stateSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
            case SPEED_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from download speed info", session.getId()));
                Optional.ofNullable(downloadSpeedSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
            case USER_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from user info", session.getId()));
                Optional.ofNullable(userSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
            case GRAB_QUEUE_UNSUB:
                logger.debug(String.format("Client %s unsubscribed from grab queue", session.getId()));
                Optional.ofNullable(grabQueueSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
                break;
        }
    }

    private void subscribeForGlobalState(WebSocketSession session) {

        logger.debug(String.format("Client %s subscribed for global state", session.getId()));
        if (stateSubscriptions.containsKey(session.getId())) {
            stateSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(Collections.singletonList(globalStateService.getCurrentState()))));
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        stateSubscriptions.put(session.getId(),
                globalStateService.getLiveGlobalState()
                        .subscribeOn(commonExecutor.getScheduler())
                        .onBackpressureBuffer()
                        .map(Collections::singleton)
                        .map(om::writeValueAsString)
                        .map(TextMessage::new)
                        .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private void subscribeForSpeed(WebSocketSession session) {

        logger.debug(String.format("Client %s subscribed for download speed info", session.getId()));
        if (downloadSpeedSubscriptions.containsKey(session.getId())) {
            downloadSpeedSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(Collections.singletonList(new DownloadSpeed(0)))));
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        downloadSpeedSubscriptions.put(session.getId(),
                downloadSpeedService.getReadBytesPerSecond()
                        .subscribeOn(commonExecutor.getScheduler())
                        .onBackpressureBuffer()
                        .map(e -> Collections.singleton(new DownloadSpeed(e)))
                        .map(om::writeValueAsString)
                        .map(TextMessage::new)
                        .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private void subscribeForUser(WebSocketSession session) {

        logger.debug(String.format("Client %s subscribed for user info", session.getId()));
        if (userSubscriptions.containsKey(session.getId())) {
            userSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(Collections.singletonList(new LoggedUser(vipergirlsAuthService.getLoggedUser())))));
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        userSubscriptions.put(session.getId(),
                vipergirlsAuthService.getLoggedInUser()
                        .subscribeOn(commonExecutor.getScheduler())
                        .onBackpressureBuffer()
                        .map(e -> Collections.singleton(new LoggedUser(e)))
                        .map(om::writeValueAsString)
                        .map(TextMessage::new)
                        .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private void subscribeForPosts(WebSocketSession session) {

        logger.debug(String.format("Client %s subscribed for posts", session.getId()));
        if (postsSubscriptions.containsKey(session.getId())) {
            postsSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(appStateExchange.getPosts().values())));
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        postsSubscriptions.put(session.getId(),
                appStateExchange.livePost()
                        .subscribeOn(commonExecutor.getScheduler())
                        .onBackpressureBuffer()
                        .buffer(2000, TimeUnit.MILLISECONDS, 200)
                        .filter(e -> !e.isEmpty())
                        .map(HashSet::new)
                        .map(om::writeValueAsString)
                        .map(TextMessage::new)
                        .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private void subscribeForGrabQueue(WebSocketSession session) {

        logger.debug(String.format("Client %s subscribed for grab queue", session.getId()));
        if (grabQueueSubscriptions.containsKey(session.getId())) {
            grabQueueSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(appStateExchange.getQueue().values())));
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        grabQueueSubscriptions.put(session.getId(),
                appStateExchange.liveQueue()
                        .subscribeOn(commonExecutor.getScheduler())
                        .onBackpressureBuffer()
                        .buffer(2000, TimeUnit.MILLISECONDS, 200)
                        .filter(e -> !e.isEmpty())
                        .map(HashSet::new)
                        .map(om::writeValueAsString)
                        .map(TextMessage::new)
                        .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private void subscribeForPostDetails(WebSocketSession session, String postId) {

        logger.debug(String.format("Client %s subscribed for post details with id = %s", session.getId(), postId));
        if (postDetailsSubscriptions.containsKey(session.getId())) {
            postDetailsSubscriptions.get(session.getId()).dispose();
        }

        try {
            send(session, new TextMessage(om.writeValueAsString(
                    appStateExchange.getImages()
                            .values()
                            .stream()
                            .filter(e -> e.getPostId().equals(postId))
                            .collect(Collectors.toSet())))
            );
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
        }

        postDetailsSubscriptions.put(session.getId(), appStateExchange.liveImage()
                .subscribeOn(commonExecutor.getScheduler())
                .onBackpressureBuffer()
                .filter(e -> e.getPostId().equals(postId))
                .buffer(2000, TimeUnit.MILLISECONDS, 500)
                .filter(e -> !e.isEmpty())
                .map(HashSet::new)
                .map(om::writeValueAsString)
                .map(TextMessage::new)
                .subscribe(msg -> send(session, msg), e -> logger.error("Failed to send data to client", e))
        );
    }

    private synchronized void send(WebSocketSession session, TextMessage message) throws IOException {
        session.sendMessage(message);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        logger.debug(String.format("Connection open for client id: %s", session.getId()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {

        Optional.ofNullable(postsSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(postDetailsSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(stateSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(downloadSpeedSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(userSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(grabQueueSubscriptions.remove(session.getId())).ifPresent(Disposable::dispose);
        Optional.ofNullable(threadParseRequests.remove(session.getId())).ifPresent(d -> d.cancel(true));

        logger.debug(String.format("Connection closed for client id: %s", session.getId()));
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class WSMessage {

        private String cmd;
        private String payload;

        enum CMD {
            POSTS_SUB,
            POST_DETAILS_SUB,
            POSTS_UNSUB,
            POST_DETAILS_UNSUB,
            GLOBAL_STATE_SUB,
            GLOBAL_STATE_UNSUB,
            SPEED_SUB,
            SPEED_UNSUB,
            USER_SUB,
            USER_UNSUB,
            GRAB_QUEUE_SUB,
            GRAB_QUEUE_UNSUB
        }
    }

    @Getter
    private static class LoggedUser {

        private final String type = "user";
        private String user;

        LoggedUser(String user) {
            this.user = user;
        }
    }
}
