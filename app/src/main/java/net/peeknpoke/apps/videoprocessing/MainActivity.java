package net.peeknpoke.apps.videoprocessing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements FrameProcessorObserver {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int PICK_FROM_GALLERY = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;

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

    private ExecutorService mExecutorService;
    private Runnable mCpuUsageRunnable;

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

        mExecutorService = Executors.newSingleThreadExecutor();

        // Initialize CSV file with a start entry
        initializeCsvFile();

        // Start CPU usage monitoring as soon as the app starts
        startCpuUsageMonitoring();

        // Check and request storage permissions if needed
        checkStoragePermission();
    }

    private void initializeCsvFile() {
        // Write an entry to the CSV file indicating the app start state
        writeToCsv(System.currentTimeMillis() + ",0," + getCpuUsage());
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

            // Write entry to CSV file with process state as 1
            writeToCsv(System.currentTimeMillis() + ",1," + getCpuUsage());
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
        if (requestCode != PERMISSION_REQUEST_CODE) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                    " Result code = " + grantResults[0]);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkStoragePermission();
    }

    @Override
    public void doneProcessing() {
        Log.d(TAG, "doneProcessing called");
        runOnUiThread(() -> {
            mFrameProcessor.removeObserver(this);
            mProgressBar.setVisibility(View.INVISIBLE);

            long endTime = System.currentTimeMillis();
            long processingTime = endTime - mStartTime;
            mProcessingTime.setText("Processing Time: " + processingTime + "ms");

            // Stop CPU usage monitoring and write final entry to CSV file
            stopCpuUsageMonitoring();

            // Write final CSV entry with process state as 0
            Log.d(TAG, "Writing final entry to CSV");
            writeToCsv(System.currentTimeMillis() + ",0," + getCpuUsage()); // Log the process stopped state
        });
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

    // Method to get the CSV file
    private File getCsvFile() {
        File directory = getExternalFilesDir(null); // Use getExternalFilesDir() for app-specific directory
        return new File(directory, "cpu_usage.csv");
    }

    // Method to write a line to the CSV file
    private void writeToCsv(String line) {
        File csvFile = getCsvFile();
        try (FileWriter writer = new FileWriter(csvFile, true)) {
            writer.append(line).append("\n");
            Log.d(TAG, "Written to CSV: " + line);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to CSV", e);
        }
    }

    // Method to start CPU usage monitoring
    private void startCpuUsageMonitoring() {
        if (mCpuUsageRunnable == null) {
            mCpuUsageRunnable = new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        final float cpuUsage = getCpuUsage();
                        runOnUiThread(() -> mCpuUsageTextView.setText("CPU Usage: " + cpuUsage));
                        // Write to CSV file on background thread
                        writeToCsv(System.currentTimeMillis() + "," + (mFrameProcessor != null ? "1" : "0") + "," + cpuUsage);

                        try {
                            Thread.sleep(100); // Adjust the interval as needed
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };

            mExecutorService.submit(mCpuUsageRunnable);
        }
    }

    // Method to stop CPU usage monitoring
    private void stopCpuUsageMonitoring() {
        if (mCpuUsageRunnable != null) {
            mExecutorService.shutdownNow();
            mCpuUsageRunnable = null;
        }
    }

    // Check and request storage permissions if needed
    private void checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCpuUsageMonitoring(); // Ensure monitoring is stopped
        if (mExecutorService != null) {
            mExecutorService.shutdownNow();
        }
    }
}
