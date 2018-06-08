/**
 * Component to orchestrate the Twilio Video connection and the various video
 * views.
 * <p>
 * Authors:
 * Ralph Pina <ralph.pina@gmail.com>
 * Jonathan Chang <slycoder@gmail.com>
 */
package com.twiliorn.library;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.View;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import com.twilio.video.RemoteAudioTrackPublication;
import com.twilio.video.CameraCapturer;
import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalAudioTrackPublication;
import com.twilio.video.LocalVideoTrackPublication;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.Room;
import com.twilio.video.RoomState;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoRenderer;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.RemoteVideoTrack;
import com.twilio.video.VideoView;
import com.twilio.video.VideoConstraints;
import com.twilio.video.VideoDimensions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_AUDIO_CHANGED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CAMERA_SWITCHED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CONNECT_FAILURE;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_DISCONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_CONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_DISCONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_VIDEO_CHANGED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_ADDED_VIDEO_TRACK;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_REMOVED_VIDEO_TRACK;

public class CustomTwilioVideoView extends View implements LifecycleEventListener {
    private static final String TAG = "CustomTwilioVideoView";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({ Events.ON_CAMERA_SWITCHED, Events.ON_VIDEO_CHANGED, Events.ON_AUDIO_CHANGED, Events.ON_CONNECTED,
            Events.ON_CONNECT_FAILURE, Events.ON_DISCONNECTED, Events.ON_PARTICIPANT_CONNECTED,
            Events.ON_PARTICIPANT_DISCONNECTED, Events.ON_PARTICIPANT_ADDED_VIDEO_TRACK,
            Events.ON_PARTICIPANT_REMOVED_VIDEO_TRACK })
    public @interface Events {
        String ON_CAMERA_SWITCHED = "onCameraSwitched";
        String ON_VIDEO_CHANGED = "onVideoChanged";
        String ON_AUDIO_CHANGED = "onAudioChanged";
        String ON_CONNECTED = "onRoomDidConnect";
        String ON_CONNECT_FAILURE = "onRoomDidFailToConnect";
        String ON_DISCONNECTED = "onRoomDidDisconnect";
        String ON_PARTICIPANT_CONNECTED = "onRoomParticipantDidConnect";
        String ON_PARTICIPANT_DISCONNECTED = "onRoomParticipantDidDisconnect";
        String ON_PARTICIPANT_ADDED_VIDEO_TRACK = "onParticipantAddedVideoTrack";
        String ON_PARTICIPANT_REMOVED_VIDEO_TRACK = "onParticipantRemovedVideoTrack";
    }

    private final ThemedReactContext themedReactContext;
    private final RCTEventEmitter eventEmitter;

    /*
     * A Room represents communication between the client and one or more
     * participants.
     */
    private Room room;
    private String roomName = null;
    private String accessToken = null;
    private LocalParticipant localParticipant;

    /*
     * A VideoView receives frames from a local or remote video track and renders
     * them to an associated view.
     */
    private static VideoView primaryVideoView;
    private static VideoView thumbnailVideoView;
    private static List<RemoteVideoTrackPublication> participantVideoTracks = new ArrayList<RemoteVideoTrackPublication>();
    private static LocalVideoTrackPublication localVideoTrack;

    private static CameraCapturer cameraCapturer;
    private LocalAudioTrackPublication localAudioTrack;
    private AudioManager audioManager;
    private int previousAudioMode;
    private boolean disconnectedFromOnDestroy;
    private IntentFilter intentFilter;
    private BecomingNoisyReceiver myNoisyAudioStreamReceiver;

    public CustomTwilioVideoView(ThemedReactContext context) {
        super(context);
        this.themedReactContext = context;
        this.eventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);

        // add lifecycle for onResume and on onPause
        themedReactContext.addLifecycleEventListener(this);

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        if (themedReactContext.getCurrentActivity() != null) {
            themedReactContext.getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }
        /*
         * Needed for setting/abandoning audio focus during call
         */
        audioManager = (AudioManager) themedReactContext.getSystemService(Context.AUDIO_SERVICE);
        myNoisyAudioStreamReceiver = new BecomingNoisyReceiver();
        intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
    }

    // ===== SETUP
    // =================================================================================

    private VideoConstraints buildVideoConstraints() {
        return new VideoConstraints.Builder().minVideoDimensions(VideoDimensions.CIF_VIDEO_DIMENSIONS)
                .maxVideoDimensions(VideoDimensions.CIF_VIDEO_DIMENSIONS).minFps(5).maxFps(15).build();
    }

    private void createLocalMedia() {
        // Share your microphone
        localAudioTrack = LocalAudioTrackPublication.create(getContext(), true);
        Log.i("CustomTwilioVideoView", "Create local media");

        // Share your camera
        cameraCapturer = new CameraCapturer(getContext(), CameraCapturer.CameraSource.FRONT_CAMERA,
                new CameraCapturer.Listener() {
                    @Override
                    public void onFirstFrameAvailable() {
                        Log.i("CustomTwilioVideoView", "Got a local camera track");
                    }

                    @Override
                    public void onCameraSwitched() {

                    }

                    @Override
                    public void onError(int i) {
                        Log.i("CustomTwilioVideoView", "Error getting camera");
                    }
                });

        if (cameraCapturer.getSupportedFormats().size() > 0) {
            localVideoTrack = LocalVideoTrackPublication.create(getContext(), true, cameraCapturer,
                    buildVideoConstraints());
            if (thumbnailVideoView != null && localVideoTrack != null) {
                localVideoTrack.addRenderer(thumbnailVideoView);
            }
            setThumbnailMirror();
        }
        connectToRoom();
    }

    // ===== LIFECYCLE EVENTS
    // ======================================================================

    @Override
    public void onHostResume() {
        /*
         * In case it wasn't set.
         */
        if (themedReactContext.getCurrentActivity() != null) {
            /*
             * If the local video track was released when the app was put in the background,
             * recreate.
             */
            if (cameraCapturer != null && localVideoTrack == null) {
                localVideoTrack = LocalVideoTrackPublication.create(getContext(), true, cameraCapturer,
                        buildVideoConstraints());
            }

            if (localVideoTrack != null) {
                if (thumbnailVideoView != null) {
                    localVideoTrack.addRenderer(thumbnailVideoView);
                }

                /*
                 * If connected to a Room then share the local video track.
                 */
                if (localParticipant != null) {
                    localParticipant.publishTrack(localVideoTrack);
                }
            }

            themedReactContext.getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        }
    }

    @Override
    public void onHostPause() {
        Log.i("CustomTwilioVideoView", "Host pause");
        /*
         * Release the local video track before going in the background. This ensures
         * that the camera can be used by other applications while this app is in the
         * background.
         */
        if (localVideoTrack != null) {
            /*
             * If this local video track is being shared in a Room, remove from local
             * remoteParticipant before releasing the video track. Participants will be
             * notified that the track has been removed.
             */
            if (localParticipant != null) {
                localParticipant.unpublishTrack(localVideoTrack);
            }

            localVideoTrack.release();
            localVideoTrack = null;
        }
    }

    @Override
    public void onHostDestroy() {
        /*
         * Always disconnect from the room before leaving the Activity to ensure any
         * memory allocated to the Room resource is freed.
         */
        if (room != null && room.getState() != RoomState.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        /*
         * Release the local media ensuring any memory allocated to audio or video is
         * freed.
         */
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
    }

    // ====== CONNECTING
    // ===========================================================================

    public void connectToRoomWrapper(String roomName, String accessToken) {
        this.roomName = roomName;
        this.accessToken = accessToken;

        Log.i("CustomTwilioVideoView", "Starting connect flow");

        if (cameraCapturer == null) {
            createLocalMedia();
        } else {
            localAudioTrack = LocalAudioTrackPublication.create(getContext(), true);
            connectToRoom();
        }
    }

    public void connectToRoom() {
        /*
         * Create a VideoClient allowing you to connect to a Room
         */
        setAudioFocus(true);
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(this.accessToken);

        if (this.roomName != null) {
            connectOptionsBuilder.roomName(this.roomName);
        }

        if (localAudioTrack != null) {
            connectOptionsBuilder.audioTracks(Collections.singletonList(localAudioTrack));
        }

        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
        }

        room = Video.connect(getContext(), connectOptionsBuilder.build(), roomListener());
    }

    private void setAudioFocus(boolean focus) {
        if (focus) {
            previousAudioMode = audioManager.getMode();
            // Request audio focus before making any device switch.
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required to be in
             * this mode when playout and/or recording starts for the best possible VoIP
             * performance. Some devices have difficulties with speaker mode if this is not
             * set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());
            getContext().registerReceiver(myNoisyAudioStreamReceiver, intentFilter);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setSpeakerphoneOn(false);
            getContext().unregisterReceiver(myNoisyAudioStreamReceiver);
        }
    }

    private class BecomingNoisyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                audioManager.setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());
            }
        }
    }

    // ====== DISCONNECTING
    // ========================================================================

    public void disconnect() {
        if (room != null) {
            room.disconnect();
        }
        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
    }

    // ===== BUTTON LISTENERS
    // ======================================================================
    private static void setThumbnailMirror() {
        if (cameraCapturer != null) {
            CameraCapturer.CameraSource cameraSource = cameraCapturer.getCameraSource();
            final boolean isBackCamera = (cameraSource == CameraCapturer.CameraSource.BACK_CAMERA);
            if (thumbnailVideoView != null && thumbnailVideoView.getVisibility() == View.VISIBLE) {
                thumbnailVideoView.setMirror(isBackCamera);
            }
        }
    }

    public void switchCamera() {
        if (cameraCapturer != null) {
            cameraCapturer.switchCamera();
            setThumbnailMirror();
            CameraCapturer.CameraSource cameraSource = cameraCapturer.getCameraSource();
            final boolean isBackCamera = cameraSource == CameraCapturer.CameraSource.BACK_CAMERA;
            WritableMap event = new WritableNativeMap();
            event.putBoolean("isBackCamera", isBackCamera);
            pushEvent(CustomTwilioVideoView.this, ON_CAMERA_SWITCHED, event);
        }
    }

    public void toggleVideo(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.enable(enabled);

            WritableMap event = new WritableNativeMap();
            event.putBoolean("videoEnabled", enabled);
            pushEvent(CustomTwilioVideoView.this, ON_VIDEO_CHANGED, event);
        }
    }

    public void toggleAudio(boolean enabled) {
        if (localAudioTrack != null) {
            localAudioTrack.enable(enabled);

            WritableMap event = new WritableNativeMap();
            event.putBoolean("audioEnabled", enabled);
            pushEvent(CustomTwilioVideoView.this, ON_AUDIO_CHANGED, event);
        }
    }

    // ====== ROOM LISTENER
    // ========================================================================

    /*
     * Room events listener
     */
    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                localParticipant = room.getLocalParticipant();
                WritableMap event = new WritableNativeMap();
                event.putString("room", room.getName());
                List<RemoteParticipant> participants = room.getRemoteParticipants();

                WritableArray participantsNames = new WritableNativeArray();
                for (RemoteParticipant remoteParticipant : participants) {
                    participantsNames.pushString(remoteParticipant.getIdentity());
                }
                event.putArray("participantsNames", participantsNames);

                pushEvent(CustomTwilioVideoView.this, ON_CONNECTED, event);

                // noinspection LoopStatementThatDoesntLoop
                for (RemoteParticipant remoteParticipant : participants) {
                    addParticipant(remoteParticipant);
                    break;
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                WritableMap event = new WritableNativeMap();
                event.putString("reason", e.getExplanation());
                pushEvent(CustomTwilioVideoView.this, ON_CONNECT_FAILURE, event);
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                WritableMap event = new WritableNativeMap();
                event.putString("remoteParticipant", localParticipant.getIdentity());
                pushEvent(CustomTwilioVideoView.this, ON_DISCONNECTED, event);

                localParticipant = null;
                roomName = null;
                accessToken = null;

                CustomTwilioVideoView.this.room = null;
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy) {
                    setAudioFocus(false);
                }
            }

            @Override
            public void onParticipantConnected(Room room, RemoteParticipant remoteParticipant) {
                addParticipant(remoteParticipant);
            }

            @Override
            public void onParticipantDisconnected(Room room, RemoteParticipant remoteParticipant) {
                removeParticipant(remoteParticipant);
            }

            @Override
            public void onRecordingStarted(Room room) {
            }

            @Override
            public void onRecordingStopped(Room room) {
            }
        };
    }

    /*
     * Called when remoteParticipant joins the room
     */
    private void addParticipant(RemoteParticipant remoteParticipant) {
        Log.i("CustomTwilioVideoView", "ADD PARTICIPANT ");

        WritableMap event = new WritableNativeMap();
        event.putString("remoteParticipant", remoteParticipant.getIdentity());

        pushEvent(this, ON_PARTICIPANT_CONNECTED, event);
        /*
         * Add remoteParticipant renderer
         */
        if (remoteParticipant.getVideoTracks().size() > 0) {
            Log.i("CustomTwilioVideoView", "RemoteParticipant DOES HAVE VIDEO TRACKS");

            addParticipantVideo(remoteParticipant, remoteParticipant.getVideoTracks().get(0));
        } else {
            Log.i("CustomTwilioVideoView", "RemoteParticipant DOES NOT HAVE VIDEO TRACKS");

        }

        /*
         * Start listening for remoteParticipant media events
         */
        remoteParticipant.setListener(mediaListener());
    }

    /*
     * Called when remoteParticipant leaves the room
     */
    private void removeParticipant(RemoteParticipant remoteParticipant) {
        WritableMap event = new WritableNativeMap();
        event.putString("remoteParticipant", remoteParticipant.getIdentity());
        pushEvent(this, ON_PARTICIPANT_DISCONNECTED, event);

        /*
         * Remove remoteParticipant renderer
         */
        if (remoteParticipant.getVideoTracks().size() > 0) {
            removeParticipantVideo(remoteParticipant, remoteParticipant.getVideoTracks().get(0));
        }
        // something about this breaking.
        // remoteParticipant.setListener(null);
    }

    // ====== MEDIA LISTENER
    // =======================================================================

    private RemoteParticipant.Listener mediaListener() {
        return new RemoteParticipant.Listener() {
            @Override
            public void onAudioTrackPublished(RemoteParticipant remoteParticipant,
                    RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackUnpublished(RemoteParticipant remoteParticipant,
                    RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            onVideoTrackPublished(RemoteParticipant remoteParticipant,
                    RemoteVideoTrackPublication remoteVideoTrackPublication) {
                Log.i("CustomTwilioVideoView", "RemoteParticipant ADDED TRACK");

                addParticipantVideo(remoteParticipant, remoteVideoTrackPublication);
            }

            @Override
            onVideoTrackUnpublished(RemoteParticipant remoteParticipant,
                    RemoteVideoTrackPublication remoteVideoTrackPublication) {
                Log.i("CustomTwilioVideoView", "RemoteParticipant REMOVED TRACK");
                removeParticipantVideo(remoteParticipant, remoteVideoTrackPublication);
            }

            @Override
            public void onAudioTrackEnabled(RemoteParicipant remoteParticipant,
                    RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackDisabled(RemoteParicipant remoteParticipant,
                    RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onVideoTrackEnabled(RemoteParicipant remoteParticipant,
                    RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }

            @Override
            public void onVideoTrackDisabled(RemoteParicipant remoteParticipant,
                    RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }
        };
    }

    private WritableMap buildParticipantVideoEvent(RemoteParicipant remoteParticipant,
            RemoteVideoTrackPublication remoteVideoTrackPublication) {
        WritableMap participantMap = new WritableNativeMap();
        participantMap.putString("identity", remoteParticipant.getIdentity());

        WritableMap trackMap = new WritableNativeMap();
        trackMap.putString("trackId", remoteVideoTrackPublication.getTrackId());

        WritableMap event = new WritableNativeMap();
        event.putMap("remoteParticipant", participantMap);
        event.putMap("track", trackMap);
        return event;
    }

    private void addParticipantVideo(RemoteParticipant remoteParticipant, RemoteVideoTrack remoteVideoTrack) {
        Log.i("CustomTwilioVideoView", "add RemoteParticipant Video");

        participantVideoTracks.add(participantVideoTracks.size(), remoteVideoTrack);

        WritableMap event = this.buildParticipantVideoEvent(remoteParticipant, remoteVideoTrack);
        pushEvent(CustomTwilioVideoView.this, ON_PARTICIPANT_ADDED_VIDEO_TRACK, event);
    }

    private void removeParticipantVideo(RemoteParticipant remoteParticipant, RemoteVideoTrack deleteVideoTrack) {
        Log.i("CustomTwilioVideoView", "Remove remoteParticipant");
        List<RemoteVideoTrackPublication> newParticipantTracks = new ArrayList<RemoteVideoTrackPublication>();
        for (RemoteVideoTrackPublication remoteVideoTrackPublication : participantVideoTracks) {
            Log.i("CustomTwilioVideoView", remoteVideoTrackPublication.getTrackId());

            if (remoteVideoTrackPublication.getTrackId().equals(deleteVideoTrack.getTrackId())) {
                Log.i("CustomTwilioVideoView", "FOUND THE MATCHING TRACK");

            } else {
                newParticipantTracks.add(newParticipantTracks.size(), remoteVideoTrackPublication);
            }
        }

        participantVideoTracks = newParticipantTracks;
        WritableMap event = this.buildParticipantVideoEvent(remoteParticipant, deleteVideoTrack);
        pushEvent(CustomTwilioVideoView.this, ON_PARTICIPANT_REMOVED_VIDEO_TRACK, event);
    }
    // ===== EVENTS TO RN
    // ==========================================================================

    void pushEvent(View view, String name, WritableMap data) {
        eventEmitter.receiveEvent(view.getId(), name, data);
    }

    public static void registerPrimaryVideoView(VideoView v, String trackId) {
        String sizeString = Integer.toString(participantVideoTracks.size());

        Log.i("CustomTwilioVideoView", "register Primary Video");
        Log.i("CustomTwilioVideoView", trackId);
        primaryVideoView = v;

        if (participantVideoTracks != null) {

            Log.i("CustomTwilioVideoView", "Found Partiipant tracks");

            for (RemoteVideoTrackPublication remoteVideoTrackPublication : participantVideoTracks) {
                Log.i("CustomTwilioVideoView", remoteVideoTrackPublication.getTrackId());

                List<VideoRenderer> renders = remoteVideoTrackPublication.getRenderers();
                if (remoteVideoTrackPublication.getTrackId().equals(trackId)) {
                    Log.i("CustomTwilioVideoView", "FOUND THE MATCHING TRACK");
                    remoteVideoTrackPublication.addRenderer(v);
                } else {
                    remoteVideoTrackPublication.removeRenderer(v);

                }
            }
        }
    }

    public static void registerThumbnailVideoView(VideoView v) {
        thumbnailVideoView = v;
        if (localVideoTrack != null) {
            localVideoTrack.addRenderer(v);
        }
        setThumbnailMirror();
    }
}
