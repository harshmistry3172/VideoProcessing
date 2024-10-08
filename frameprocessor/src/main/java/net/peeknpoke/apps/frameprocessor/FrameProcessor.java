package net.peeknpoke.apps.frameprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class FrameProcessor implements RendererObserver, ObserverSubject<FrameProcessorObserver> {
    private static final String TAG = FrameProcessor.class.getSimpleName();
    private CustomContext mRenderingContext;
    private MediaCodec mMediaCodec;
    private MediaExtractor mMediaExtractor;
    private Handler mMainHandler;
    private MediaFormat mMediaFormat;
    private int mMaxFrames;
    private ArrayList<WeakReference<FrameProcessorObserver>> mObservers = new ArrayList<>();

    public FrameProcessor(final Context context, Uri uri, int maxFrames, String appName) throws IOException {
        mMediaExtractor = new MediaExtractor();
        mMediaExtractor.setDataSource(context, uri, null);
        mMaxFrames = maxFrames;
        mMainHandler = new Handler(context.getMainLooper());

        final Handler renderingHandler = createRenderingThread();

        int videoTrackIndex = getVideoTrackIndex(mMediaExtractor);
        if (videoTrackIndex <0)
        {
            Log.e(TAG, "No video track");
        }
        else
        {
            mMediaExtractor.selectTrack(videoTrackIndex);
            mMediaFormat = mMediaExtractor.getTrackFormat(videoTrackIndex);
            int width = mMediaFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = mMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
//            int rotation = mMediaFormat.getInteger(MediaFormat.KEY_ROTATION);
//            if (rotation==90 || rotation==270)
//            {
//                int temp = width;
//                width = height;
//                height = temp;
//            }
            mRenderingContext = new CustomContext(context, width, height, maxFrames, appName);
            mRenderingContext.registerObserver(this);
            renderingHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRenderingContext.setupRenderingContext(context);
                }
            });
        }
    }

    private void start()
    {
        mMediaCodec.start();
    }

    private Handler createRenderingThread()
    {
        HandlerThread renderingThread = new HandlerThread("CustomContext");
        renderingThread.start();
        Looper looper = renderingThread.getLooper();
        return new Handler(looper);
    }

    private void stop()
    {
        if (mMediaCodec!=null)
        {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
    }

    private void setupMediaCodec(MediaFormat mediaFormat) throws IOException
    {
        String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        if (mimeType==null)
        {
            Log.e(TAG, "Could not read mime type");
            return;
        }
        mMediaCodec = MediaCodec.createDecoderByType(mimeType);
        mMediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                Log.d(TAG, "Filling buffer: "+index);
                fillInputBuffer(inputBuffer, index);
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "Processing output buffer "+index+" size: "+info.size);
                processOutputBuffer(info, index);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Media codec error - "+e.getMessage()+" - "+e.getErrorCode());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            }
        });

        mMediaCodec.configure(mediaFormat, mRenderingContext.getSurface(), null, 0);
    }

    private void fillInputBuffer(ByteBuffer inputBuffer, int index)
    {
        int sampleSize = mMediaExtractor.readSampleData(inputBuffer, 0);
        Log.d(TAG, "sample size: "+sampleSize);
        if (sampleSize < 0)
        {
            // End of input data reached
            mMediaCodec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            Log.d(TAG, "input EOS");
        }
        else
        {
            mMediaCodec.queueInputBuffer(index, 0, sampleSize,
                    mMediaExtractor.getSampleTime(), 0);

            mMediaExtractor.advance();
        }
    }

    private void processOutputBuffer(MediaCodec.BufferInfo info, int index)
    {
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "output EOS");
        }

        mMediaCodec.releaseOutputBuffer(index, info.size != 0);
        if (info.size!=0)
        {
            synchronized (mRenderingContext.sync)
            {
                try {
                    while(!mRenderingContext.proceed)
                        mRenderingContext.sync.wait(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mRenderingContext.proceed = false;
                if (mRenderingContext.mOutputFrameIndex==mMaxFrames)
                    stopDecoding();
            }
        }
        else
            stopDecoding();
    }

    private int getVideoTrackIndex(MediaExtractor extractor)
    {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime!=null && mime.startsWith("video/"))
                return i;
        }

        return -1;
    }

    public void release()
    {
        stop();
        mRenderingContext.removeObserver(this);
        mRenderingContext.release();
    }

    private void renderingSurfaceCreated() {
        try {
            setupMediaCodec(mMediaFormat);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Create media decoder
        // Note: this needs the surface created in CustomContext. So order cannot change
        start();
    }

    private void stopDecoding() {
        stop();
        notifyObservers();
    }

    @Override
    public void setupComplete() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                renderingSurfaceCreated();
            }
        });
    }

    private WeakReference<FrameProcessorObserver> findWeakReference(FrameProcessorObserver rendererObserver)
    {
        WeakReference<FrameProcessorObserver> weakReference = null;
        for(WeakReference<FrameProcessorObserver> ref : mObservers) {
            if (ref.get() == rendererObserver) {
                weakReference = ref;
            }
        }
        return weakReference;
    }

    @Override
    public void registerObserver(FrameProcessorObserver observer) {
        WeakReference<FrameProcessorObserver> weakReference = findWeakReference(observer);
        if (weakReference==null)
            mObservers.add(new WeakReference<>(observer));
    }

    @Override
    public void removeObserver(FrameProcessorObserver observer) {
        WeakReference<FrameProcessorObserver> weakReference = findWeakReference(observer);
        if (weakReference != null) {
            mObservers.remove(weakReference);
        }
    }

    private void notifyObservers() {
        for (WeakReference<FrameProcessorObserver> co:mObservers){
            FrameProcessorObserver observer = co.get();
            if (observer!=null)
                observer.doneProcessing();
        }
    }
}
