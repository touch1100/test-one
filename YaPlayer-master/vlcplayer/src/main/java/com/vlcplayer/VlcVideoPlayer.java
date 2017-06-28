package com.vlcplayer;


import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;


import com.log.Logger;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.vlc.listener.MediaListenerEvent;
import org.videolan.vlc.listener.MediaPlayerControl;
import org.videolan.vlc.listener.VideoSizeChange;
import org.videolan.vlc.util.VLCInstance;

import static android.os.Build.VERSION_CODES.KITKAT;


public class VlcVideoPlayer implements MediaPlayerControl, Handler.Callback, IVLCVout.Callback {
    private static final HandlerThread sThread = new HandlerThread("VlcVideoPlayThread");
    private Handler mVideoHandler;
    private Handler mainHandler;

    private static final int MSG_START = 0x0008;
    private static final int MSG_STOP = 0x0009;

    private String TAG = "VideoMediaLogic";

    private static MediaPlayer staticMediaPlayer;

    private static MediaPlayer getMediaPlayer(Context context) {

        if (staticMediaPlayer == null) {
            staticMediaPlayer = new MediaPlayer(VLCInstance.get(context));
        }
        return staticMediaPlayer;
    }

    static {
        sThread.start();
    }

    private static boolean isInstance = true;//是否单例播放   默认开
    private static boolean isSaveState;//跳转界面时 信息保存


    public void setInstance(boolean isInstance) {
        VlcVideoPlayer.isInstance = isInstance;
    }


    @Override
    public boolean handleMessage(Message msg) {
        synchronized (VlcVideoPlayer.class) {
            switch (msg.what) {
                case MSG_START:
                    Logger.i(TAG, "-----HandlerThread init 1=" + isInitStart);
                    if (isInitStart)
                        opendVideo();
                    Logger.i(TAG, "-----HandlerThread init 2=" + isInitStart);
                    break;
                case MSG_STOP:
                    Logger.i(TAG, "-----HandlerThread stop 3=" + isInitStart);
                    release();
                    Logger.i(TAG, "-----HandlerThread stop 4=" + isInitStart);
                    break;
            }
        }

        return true;
    }

    private boolean isInitStart;
    /**
     * 初始化全部参数
     */
    private boolean isInitPlay;
    /**
     * 双线程异步状态中
     */
    private boolean isSufaceDelayerPlay;//布局延迟加载播放
    private boolean isLoop = true;

    private SurfaceTexture surface;

    /**
     * 能否快进
     */
    private boolean canSeek;//能否快进
    private boolean canPause;//能否快进
    private boolean canInfo;//能否拿到信息
    private boolean isPlayError;
    private boolean isAttached;
    private boolean isAttachSurface;
    private boolean isViewLife;

    private boolean othereMedia;
    private Context mContext;

    public VlcVideoPlayer(Context context) {
        mContext = context.getApplicationContext();
        mVideoHandler = new Handler(sThread.getLooper(), this);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setMediaPlayer(LibVLC libVLC) {
        mMediaPlayer = new MediaPlayer(libVLC);
    }

    public void setMedia(Media media) {
        othereMedia = true;
        if (mMediaPlayer == null) mMediaPlayer = getMediaPlayer(mContext);
        mMediaPlayer.setMedia(media);
    }

    /**
     * 解析器
     */
    private LibVLC libVLC;
    private MediaPlayer mMediaPlayer;
    private String path;
    private final String tag = "VideoMediaLogic";
    private boolean isABLoop;
    private long abTimeStart;
    private long abTimeEnd;

    //绑定Surface
    public void setSurface(SurfaceTexture surface) {
        isAttached = true;
        this.surface = surface;
        if (isSufaceDelayerPlay) {
            isSufaceDelayerPlay = false;
            startPlay(path);
        }
    }

    public void onSurfaceTextureDestroyed() {
        isAttached = false;
        if (isAttachSurface) {
            isAttachSurface = false;
            pause();
            mMediaPlayer.getVLCVout().detachViews();
        }
    }

    public void onAttachedToWindow(boolean isViewLife) {
        this.isViewLife = isViewLife;
    }

    @Override
    public void setLoop(boolean isLoop) {
        this.isLoop = isLoop;
    }

    @Override
    public boolean isLoop() {
        return isLoop;
    }

    public void onStop() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isViewLife && mediaListenerEvent != null)
                    mediaListenerEvent.eventPlayInit(false);
                isViewLife = false;
            }
        });
        mVideoHandler.obtainMessage(MSG_STOP).sendToTarget();
    }


    private void opendVideo() {
        if (null == path || "".equals(path.trim()) || mContext == null || !isAttached) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isViewLife && mediaListenerEvent != null)
                        mediaListenerEvent.eventError(2, true);
                }
            });
            return;
        }
        isSufaceDelayerPlay = false;
        canSeek = false;
        canPause = false;
        isPlayError = false;
        if (libVLC == null) {
            libVLC = VLCInstance.get(mContext);
        }
        if (mMediaPlayer == null) {
            if (isInstance) {
                mMediaPlayer = getMediaPlayer(mContext);
            } else {
                mMediaPlayer = new MediaPlayer(VLCInstance.get(mContext));
            }
        }
        if (!isAttachSurface && mMediaPlayer.getVLCVout().areViewsAttached()) {
            mMediaPlayer.getVLCVout().detachViews();
        }
        if (!othereMedia) {
            loadMedia();
        }
        if (!mMediaPlayer.getVLCVout().areViewsAttached() && isAttached && surface != null) {
            isAttachSurface = true;
            mMediaPlayer.getVLCVout().setVideoSurface(surface);
            mMediaPlayer.getVLCVout().attachViews();
            mMediaPlayer.getVLCVout().addCallback(this);
            Logger.i(tag, "setVideoSurface   attachViews");
        }
        mMediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                onEventNative(event);
            }
        });
        isInitPlay = true;
        othereMedia = false;
        Logger.i(tag, "isAttached=" + isAttached + " isInitStart=" + isInitStart);
        if (isAttached && isInitStart && isAttachSurface) {
            mMediaPlayer.play();
        }


    }

    /**
     */
    private void loadMedia() {
        if (isSaveState) {
            isSaveState = false;
            Media media = mMediaPlayer.getMedia();
            if (media != null && !media.isReleased()) {
                canSeek = true;
                canPause = true;
                canInfo = true;
                return;
            }
        }
        //http://www.baidu
        //rtmp://58.61.150.198/live/Livestream
        // ftp://www.baidu
        // sdcard/mp4.mp4
        if (path.contains("://")) {
            final Media media = new Media(libVLC, Uri.parse(path));
            if (Build.VERSION.SDK_INT <= KITKAT) {
                media.setHWDecoderEnabled(false, false);
            }

            media.setEventListener(mMediaListener);
            media.parseAsync(Media.Parse.FetchNetwork, 5 * 1000);
            mMediaPlayer.setMedia(media);
            media.release();
        } else {
            final Media media = new Media(libVLC, path);
            if (Build.VERSION.SDK_INT <= KITKAT) {
                media.setHWDecoderEnabled(false, false);
            }
            media.setEventListener(mMediaListener);
            //    media.parseAsync(Media.Parse.FetchLocal);
            mMediaPlayer.setMedia(media);
            media.release();
        }

    }

    private final Media.EventListener mMediaListener = new Media.EventListener() {
        @Override
        public void onEvent(Media.Event event) {
            boolean update = true;
            switch (event.type) {
                case Media.Event.MetaChanged:
                    Logger.i(TAG, "Media.Event.MetaChanged:  =" + event.getMetaId());
                    break;
                case Media.Event.ParsedChanged:
                    Logger.i(TAG, "Media.Event.ParsedChanged  =" + event.getMetaId());
                    break;
                case Media.Event.StateChanged:
                    Logger.i(TAG, "StateChanged   =" + event.getMetaId());
                    break;
                default:
                    Logger.i(TAG, "Media.Event.type=" + event.type + "   eventgetParsedStatus=" + event.getParsedStatus());
                    update = false;
                    break;

            }
            if (update) {

            }
        }
    };


    public void saveState() {
        if (isInitPlay) {
            isSaveState = true;
            onStop();
        }
    }

    @Override
    public void startPlay(String path) {
        this.path = path;
        isInitStart = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                isViewLife = true;
                if (isViewLife && mediaListenerEvent != null)
                    mediaListenerEvent.eventPlayInit(true);
            }
        });
        if (isAttached) {
            mVideoHandler.obtainMessage(MSG_START).sendToTarget();
        } else {
            isSufaceDelayerPlay = true;
        }
    }

    private void reStartPlay() {
        if (isAttached && isLoop && isPrepare()) {
            Logger.i(tag, "reStartPlay setMedia");
            mMediaPlayer.setMedia(mMediaPlayer.getMedia());
            if (isAttached)
                mMediaPlayer.play();
        } else {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isViewLife && mediaListenerEvent != null)
                        mediaListenerEvent.eventStop(isPlayError);
                }
            });
        }
    }


    private void release() {
        Logger.i(tag, "release");
        canSeek = false;
        if (mMediaPlayer != null && isInitPlay) {
            Logger.i(tag, "release SaveState  isAttachSurface=" + isAttachSurface);
            isInitPlay = false;
            mMediaPlayer.getVLCVout().removeCallback(this);
            if (isAttachSurface) {
                isAttachSurface = false;
                mMediaPlayer.getVLCVout().detachViews();
            }
            if (isSaveState) {
                if (canPause)
                    mMediaPlayer.pause();
            } else {
                final Media media = mMediaPlayer.getMedia();
                if (media != null) {
                    media.setEventListener(null);
                    mMediaPlayer.stop();
                    Logger.i(tag, "release setMedia null");
                    mMediaPlayer.setMedia(null);
                    media.release();
                    isSaveState = false;
                }
            }
            Logger.i(tag, "release over");
        }
        canPause = false;
        isInitStart = false;
    }

    public void onDestory() {
        if (mMediaPlayer != null) {
            if (!mMediaPlayer.isReleased()) {
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
        }
    }

    private void onEventNative(final MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Stopped:
                canInfo = false;
                canSeek = false;
                canPause = false;
                Logger.i(tag, "Stopped  isLoop=" + isLoop + "  ");
                reStartPlay();
                break;
            case MediaPlayer.Event.EndReached:
                Logger.i(tag, "EndReached");
                break;
            case MediaPlayer.Event.EncounteredError:
                isPlayError = true;
                canInfo = false;
                Logger.i(tag, "EncounteredError");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isViewLife && mediaListenerEvent != null)
                            mediaListenerEvent.eventError(1, true);
                    }
                });
                break;
            case MediaPlayer.Event.Opening:
                Logger.i(tag, "Opening");
                canInfo = true;
                speed = 1f;
                mMediaPlayer.setRate(1f);
                break;
            case MediaPlayer.Event.Playing:
                Logger.i(tag, "Playing");
                canInfo = true;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isViewLife && mediaListenerEvent != null)
                            mediaListenerEvent.eventPlay(true);
                    }
                });
                if (!isAttached || !mMediaPlayer.getVLCVout().areViewsAttached()) {//禁止双线程后台运行
                    Logger.i(tag, "---多线程出错----没有surface  禁止双线程后台运行");
                    //  mMediaPlayer.stop();
                    if (mMediaPlayer != null) {
                        mMediaPlayer.pause();
                    }
                }
                break;
            case MediaPlayer.Event.Paused:
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isViewLife && mediaListenerEvent != null)
                            mediaListenerEvent.eventPlay(false);
                    }
                });
                Logger.i(tag, "Paused");

                break;
            case MediaPlayer.Event.TimeChanged://TimeChanged   15501
                break;
            case MediaPlayer.Event.PositionChanged://PositionChanged   0.061593015
                // LogUtils.i(tag, "PositionChanged" + event.getPositionChanged());

                break;
            case MediaPlayer.Event.Vout:
                Logger.i(tag, "Vout" + event.getVoutCount());
                break;
            case MediaPlayer.Event.ESAdded:
                Logger.i(tag, "ESAdded");
                break;
            case MediaPlayer.Event.ESDeleted:
                Logger.i(tag, "ESDeleted");
                break;
            case MediaPlayer.Event.SeekableChanged:
                canSeek = event.getSeekable();
                Logger.i(tag, "SeekableChanged=" + canSeek);
                break;
            case MediaPlayer.Event.PausableChanged:
                canPause = event.getPausable();
                Logger.i(tag, "PausableChanged=" + canPause);
                break;
            case MediaPlayer.Event.Buffering:
                if (event.getBuffering() == 100f) {
                    Logger.i(TAG, "MediaPlayer.Event.Buffering" + event.getBuffering());
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isViewLife && mediaListenerEvent != null)
                            mediaListenerEvent.eventBuffing(event.getBuffering(), event.getBuffering() < 100f);
                    }
                });

                break;
            case MediaPlayer.Event.MediaChanged:
                Logger.i(tag, "MediaChanged=" + event.getEsChangedType());
                break;
            default:
                Logger.i(tag, "event.type=" + event.type);
                break;
        }
    }


    @Override
    public boolean isPrepare() {
        return mMediaPlayer != null && isInitPlay && !isPlayError;
    }

    @Override
    public boolean canControl() {
        return canPause && canInfo && canSeek;
    }

    @Override
    public void start() {
        Logger.i(tag, "start");
        if (isPrepare())
            mMediaPlayer.play();
    }

    @Override
    public void pause() {
        if (isPrepare() && canPause) {
            mMediaPlayer.pause();
        }
    }


    @Override
    public long getDuration() {
        if (isPrepare() && canInfo) {
            return mMediaPlayer.getLength();
        }
        return 0;
    }

    @Override
    public long getCurrentPosition() {
        if (isPrepare() && canInfo)
            return (long) (getDuration() * mMediaPlayer.getPosition());
        return 0;
    }

    private boolean isSeeking;

    @Override
    public void seekTo(long pos) {
        if (isPrepare() && canSeek && !isSeeking) {
            // mMediaPlayer.setTime(pos);
            isSeeking = true;
            mMediaPlayer.setTime(pos);
            isSeeking = false;
            //  mMediaPlayer.setPosition((float) pos / (float) getDuration());
            // LogUtils.i("flost=" + (pos / getDuration()));
            //    mMediaPlayer.setPosition(pos);
        }
    }


    public void setABLoop(boolean isABLoop) {
        this.isABLoop = isABLoop;

    }


    public boolean setABLoop(long abTimeA, long abTimeB) {
        if (isABLoop && isPrepare() && canSeek) {
            if (abTimeB - abTimeA < 1000) {//必须大于1秒
                Logger.i(tag, "时间少于1秒");
                return false;
            }
            this.abTimeStart = abTimeA;
            this.abTimeEnd = abTimeB;
            mMediaPlayer.setTime(abTimeA);
            return true;
        } else {
            this.abTimeStart = 0;
            this.abTimeEnd = 0;
            return false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (isPrepare()) {
            return mMediaPlayer.isPlaying();
        }
        return false;
    }

    @Override
    public void setMirror(boolean mirror) {
    }

    @Override
    public boolean getMirror() {
        return false;
    }

    /**
     * @return 没空做了
     */
    @Override
    public int getBufferPercentage() {
        return 0;
    }

    private float speed = 1f;

    @Override
    public boolean setPlaybackSpeedMedia(float speed) {
        if (isPrepare() && canSeek) {
            this.speed = speed;
            mMediaPlayer.setRate(speed);
            seekTo(getCurrentPosition());
        }
        return true;
    }

    @Override
    public float getPlaybackSpeed() {
        if (isPrepare() && canSeek)
            return mMediaPlayer.getRate();
        return speed;
    }


    /**
     * 音乐开始播放
     *
     * @param playing
     */
    public void setPause(boolean playing) {
        if (playing) {//音乐开始
            pause();
        }
    }

    private MediaListenerEvent mediaListenerEvent;

    public void setMediaListenerEvent(MediaListenerEvent mediaListenerEvent) {
        this.mediaListenerEvent = mediaListenerEvent;
    }


    public void setVideoSizeChange(VideoSizeChange videoSizeChange) {
        this.videoSizeChange = videoSizeChange;
    }

    private VideoSizeChange videoSizeChange;

    @Override
    public void onNewLayout(IVLCVout vlcVout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (videoSizeChange != null) {
            videoSizeChange.onVideoSizeChanged(width, height, visibleWidth, visibleHeight, sarNum, sarDen);
        }
    }

    @Override
    public void onSurfacesCreated(IVLCVout vlcVout) {

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vlcVout) {

    }
}
