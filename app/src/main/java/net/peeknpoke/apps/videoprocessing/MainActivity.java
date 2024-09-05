
package net.peeknpoke.apps.videoprocessing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import net.peeknpoke.apps.frameprocessor.FrameProcessor;
import net.peeknpoke.apps.frameprocessor.FrameProcessorObserver;
import net.peeknpoke.apps.videoprocessing.permissions.StoragePermissionHandler;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity implements FrameProcessorObserver {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int PICK_FROM_GALLERY = 1;
    private StoragePermissionHandler mStoragePermissionHandler;

    private VideoView mVideoView;
    private Button mProcessButton;
    private FrameProcessor mFrameProcessor;
    private Uri mVideoUri;
    private ProgressBar mProgressBar;
    private EditText mFramesInput;
    private TextView mProcessingTime;
    private TextView mCpuUsageTextView;
    private long mStartTime;
    private long mLastCpuTotalTime = 0;
    private long mLastCpuIdleTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStoragePermissionHandler = new StoragePermissionHandler(getResources().getString(R.string.app_name));
        mVideoView = findViewById(R.id.videoView);
        mProcessButton = findViewById(R.id.process);
        mProgressBar = findViewById(R.id.processingBar);
        mFramesInput = findViewById(R.id.framesInput);
        mProcessingTime = findViewById(R.id.processingTime);
        mCpuUsageTextView = findViewById(R.id.cpuUsage);

        // Start monitoring CPU usage
        startCpuUsageMonitoring();
    }

    public void onLoad(View view) {
        Intent galleryIntent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(galleryIntent, PICK_FROM_GALLERY);
    }

    public void onProcess(View view) {
        String framesText = mFramesInput.getText().toString();
        if (framesText.isEmpty()) {
            // Optionally, show a toast message or error if no frames input
            return;
        }
        mProgressBar.bringToFront();
        mProgressBar.setVisibility(View.VISIBLE);
        int numberOfFrames = Integer.parseInt(framesText);
        mStartTime = System.currentTimeMillis(); // Record start time
        try {
            mFrameProcessor = new FrameProcessor(getApplicationContext(), mVideoUri,
                    numberOfFrames,
                    getResources().getString(R.string.app_name));
            mFrameProcessor.registerObserver(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mFrameProcessor != null) {
            mFrameProcessor.release();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FROM_GALLERY && resultCode == RESULT_OK && data != null && data.getData() != null) {
            mVideoUri = data.getData();
            mVideoView.setVideoURI(mVideoUri);
            mVideoView.setOnPreparedListener(mp -> {
                mVideoView.start();
                mProcessButton.setVisibility(View.VISIBLE);
            });

            mVideoView.setOnCompletionListener(mp -> mProcessButton.setVisibility(View.VISIBLE));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != StoragePermissionHandler.CODE) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                mStoragePermissionHandler.checkAndRequestPermission(this, requestCode);

                Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                        " Result code = " + grantResults[0]);
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStoragePermissionHandler.checkAndRequestPermission(MainActivity.this, StoragePermissionHandler.CODE);
    }

    @Override
    public void doneProcessing() {
        mFrameProcessor.removeObserver(this);
        runOnUiThread(() -> mProgressBar.setVisibility(View.INVISIBLE));
        long endTime = System.currentTimeMillis();
        long processingTime = endTime - mStartTime;
        runOnUiThread(() -> mProcessingTime.setText("Processing Time: " + processingTime + "ms"));
    }

    // Method to get CPU utilization with root access
    private float getCpuUsage() {
        try {
            // Execute shell command with root access
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("cat /proc/stat\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            System.out.println("CPU Usage" + line);
            reader.close();

            if (line == null) {
                return 0;
            }

            String[] toks = line.split(" ");
            long totalCpuTime = 0;
            long idleCpuTime = 0;

            for (int i = 2; i < toks.length; i++) {
                totalCpuTime += Long.parseLong(toks[i]);
            }

            idleCpuTime = Long.parseLong(toks[4]);

            if (mLastCpuTotalTime == 0 && mLastCpuIdleTime == 0) {
                mLastCpuTotalTime = totalCpuTime;
                mLastCpuIdleTime = idleCpuTime;
                return 0;
            }

            long totalDelta = totalCpuTime - mLastCpuTotalTime;
            long idleDelta = idleCpuTime - mLastCpuIdleTime;

            mLastCpuTotalTime = totalCpuTime;
            mLastCpuIdleTime = idleCpuTime;

            return (totalDelta == 0) ? 0 : (1 - (idleDelta / (float) totalDelta)) * 100;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    // Method to periodically update CPU usage
    private void startCpuUsageMonitoring() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable updateCpuUsageRunnable = new Runnable() {
            @Override
            public void run() {
                float cpuUsage = getCpuUsage();
                mCpuUsageTextView.setText(String.format("CPU Usage: %.2f%%", cpuUsage));

                // Update every 2 seconds
                handler.postDelayed(this, 100);
            }
        };

        // Start the monitoring
        handler.post(updateCpuUsageRunnable);
    }
}

