package com.ngcomputing.fora.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h2>FileManagerActivity</h2>
 *
 * A robust, atomic file manager Activity for Android apps, designed for safe user-driven import and export of files
 * and folders between the Android Storage Access Framework (SAF) and an app-private "Documents" directory.
 *
 * <h2>Version</h2>
 * <ul>
 *   <li>2.0 (2026-01-06)</li>
 *   <li>1.1 (2025-05-29)</li>
 *   <li>1.0 (2025-05-27)</li>
 * </ul>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Import files or entire folders from user-picked SAF locations into the app's documents directory</li>
 *   <li>Export files or folders from the app's documents directory to any user-picked SAF folder</li>
 *   <li>Rename and delete files/folders in the app's documents directory</li>
 *   <li>Multi-file selection support for imports</li>
 *   <li>Progress and cancellation dialogs for long operations</li>
 *   <li>No permissions required: all file access is performed via SAF or app-private storage</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <ul>
 *   <li>Integrate as a standalone Activity in your Android application to give users a simple, safe file manager UI</li>
 *   <li>UI is built programmatically; no XML required</li>
 *   <li>All user actions are confirmed via dialogs</li>
 *   <li>Can be extended or customized as needed for your application's workflow</li>
 * </ul>
 *
 * <h2>Safety & Robustness</h2>
 * <ul>
 *   <li>Atomic copy: All files/folders are copied to hidden temp files/dirs (with ".copying" suffix), then renamed on success</li>
 *   <li>Crash/cancel recovery: On startup, unfinished ".copying" files/dirs are automatically deleted</li>
 *   <li>Name collision: All import/export aborts if a file/folder with the same name already exists at the destination</li>
 *   <li>Cancellation: User can cancel any copy operation at any point, with guaranteed cleanup of partial data</li>
 *   <li>No external storage permissions required: all file/folder access is user-scoped via SAF or app-private storage</li>
 *   <li>Storage space validation: Operations check available space before copying</li>
 *   <li>Memory leak prevention: Activity references are weakly held in background operations</li>
 * </ul>
 *
 * <h2>Implementation Details</h2>
 * <ul>
 *   <li>App Documents Directory: <code>getExternalFilesDir(null)/Documents</code>; private to the app</li>
 *   <li>SAF: All user-accessible file/folder import/export uses SAF intents (ACTION_OPEN_DOCUMENT, ACTION_OPEN_DOCUMENT_TREE)</li>
 *   <li>Threading: File operations run on background threads; UI updates are always posted to the main thread</li>
 *   <li>Recursion: Folder imports/exports are iterative to prevent stack overflows</li>
 *   <li>File/folder name checks: By default, only "/" and null bytes are blocked; override for stricter validation if needed</li>
 *   <li>Buffer size: 64KB buffers for optimal I/O performance</li>
 * </ul>
 *
 * <h2>Extending</h2>
 * <ul>
 *   <li>To change the app documents root, edit <code>appDocumentsDir</code> initialization</li>
 *   <li>To add file filters, metadata, or other UI, extend copy logic and/or UI as needed</li>
 * </ul>
 *
 * <h2>Author</h2>
 * <ul>
 *   <li>GitHub Copilot</li>
 * </ul>
 *
 * <h2>License</h2>
 * <ul>
 *   <li>LGPL - GNU Lesser General Public License</li>
 * </ul>
 */
@SuppressLint({"NotifyDataSetChanged", "SetTextI18n"})
public class FileManagerActivity extends AppCompatActivity {
    private static final String TAG = "FileManagerActivity";
    private static final int BUFFER_SIZE = 65536; // 64KB buffer for better I/O performance
    private static final long STORAGE_SAFETY_MARGIN_BYTES = 10 * 1024 * 1024; // 10MB safety margin
    
    // App-private root directory for storing documents.
    private File appDocumentsDir;
    private FileListAdapter adapter;
    private final List<File> fileList = new ArrayList<>();
    // Holds the file/folder selected for an operation (export, delete, etc.).
    private File fileToExportOrDelete = null;
    // Flag to distinguish between file and folder export operations.
    private boolean isExportingFolder = false;

    private AlertDialog progressDialog;
    private FileViewModel fileViewModel;

    // ActivityResultLaunchers for handling results from SAF pickers.
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> deviceFolderPickerLauncher;

    /**
     * ViewModel to manage background file operations and cancellation state.
     * This survives configuration changes and ensures operations are not lost.
     */
    public static class FileViewModel extends ViewModel {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private volatile boolean cancelRequested = false;

        public void execute(Runnable task) {
            cancelRequested = false;
            executor.execute(task);
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }

        public void setCancelRequested(boolean cancel) {
            this.cancelRequested = cancel;
        }

        @Override
        protected void onCleared() {
            super.onCleared();
            executor.shutdownNow(); // Ensure the background thread is terminated.
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewModel for managing background tasks.
        fileViewModel = new ViewModelProvider(this).get(FileViewModel.class);

        // Configure window to draw behind system bars for an edge-to-edge UI.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(true);
        wic.setAppearanceLightNavigationBars(true);

        // Set up the app-private "Documents" directory.
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir == null) {
            showErrorAndExit();
            return;
        }
        appDocumentsDir = new File(externalFilesDir, "Documents");
        if ((!appDocumentsDir.exists() && !appDocumentsDir.mkdirs()) || !appDocumentsDir.isDirectory()) {
            showErrorAndExit(); // Critical failure if directory can't be created.
            return;
        }

        // Clean up any partial files from previous crashed/cancelled operations.
        cleanUpCopyingArtifacts(appDocumentsDir);

        // Launcher for importing one or more files.
        filePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) { // Handle multi-file selection.
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    uris.add(uri);
                    // Persist permission for long-term access.
                    takePersistableUriPermissionSafely(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } else if (data.getData() != null) { // Handle single file selection.
                Uri uri = data.getData();
                uris.add(uri);
                // Persist permission for long-term access.
                takePersistableUriPermissionSafely(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (!uris.isEmpty()) {
                startCopyFileWithProgress(uris);
            }
        });

        // Launcher for importing a folder.
        folderPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri treeUri = result.getData().getData();
            if (treeUri != null) {
                // Persist permission for long-term access.
                takePersistableUriPermissionSafely(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startCopyFolderWithProgress(treeUri);
            }
        });

        // Launcher for selecting a destination folder for export.
        deviceFolderPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri destTreeUri = result.getData().getData();
            if (fileToExportOrDelete != null && destTreeUri != null) {
                // Persist permission for long-term access.
                takePersistableUriPermissionSafely(destTreeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (isExportingFolder) {
                    startCopyFolderToDeviceFolderWithProgress(fileToExportOrDelete, destTreeUri);
                } else {
                    startCopyFileToDeviceFolderWithProgress(fileToExportOrDelete, destTreeUri);
                }
            }
        });

        // Programmatically create the UI layout.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Adjust padding to account for system bars (status bar, navigation bar).
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    dp(24) + systemBars.left,
                    dp(24) + systemBars.top,
                    dp(24) + systemBars.right,
                    dp(24) + systemBars.bottom
            );
            return insets;
        });

        Button btnCopyFile = new Button(this);
        btnCopyFile.setText("Copy File to App Documents");
        root.addView(btnCopyFile);

        Button btnCopyFolder = new Button(this);
        btnCopyFolder.setText("Copy Folder to App Documents");
        LinearLayout.LayoutParams folderBtnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        folderBtnParams.topMargin = dp(8);
        btnCopyFolder.setLayoutParams(folderBtnParams);
        root.addView(btnCopyFolder);

        TextView label = new TextView(this);
        label.setText("App Documents:");
        label.setTextSize(16f);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setGravity(Gravity.START);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(16);
        label.setLayoutParams(labelParams);
        root.addView(label);

        RecyclerView recyclerView = new RecyclerView(this);
        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        recyclerView.setLayoutParams(recyclerParams);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        root.addView(recyclerView);

        setContentView(root);

        // Setup RecyclerView adapter for the file list.
        adapter = new FileListAdapter(fileList);
        recyclerView.setAdapter(adapter);

        btnCopyFile.setOnClickListener(v -> openFilePicker());
        btnCopyFolder.setOnClickListener(v -> openFolderPicker());

        adapter.setOnItemClickListener(this::onFileOrFolderClicked);

        // Load the initial list of files from the documents directory.
        refreshFileList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel ongoing operations when Activity is being destroyed
        if (isFinishing()) {
            fileViewModel.setCancelRequested(true);
        }
    }

    /**
     * Safely attempts to take persistable URI permission, handling SecurityException.
     * 
     * @param uri The URI to take permission for
     * @param flags Permission flags
     */
    private void takePersistableUriPermissionSafely(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to take persistable URI permission for: " + uri, e);
            showOperationResultDialog("Permission Error", 
                "Cannot access this location. The file or folder may no longer be available.");
        }
    }

    /**
     * Checks if there is enough storage space available.
     * 
     * @param requiredBytes Number of bytes required
     * @return true if enough space is available plus safety margin
     */
    private boolean hasEnoughStorageSpace(long requiredBytes) {
        try {
            StatFs stat = new StatFs(appDocumentsDir.getPath());
            long availableBytes = stat.getAvailableBytes();
            return availableBytes > (requiredBytes + STORAGE_SAFETY_MARGIN_BYTES);
        } catch (Exception e) {
            Log.e(TAG, "Failed to check storage space", e);
            return false; // Fail safe
        }
    }

    /**
     * Gets the size of a file or recursively calculates folder size.
     * 
     * @param file File or folder to measure
     * @return Size in bytes
     */
    private long getFileOrFolderSize(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long size = 0;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                size += getFileOrFolderSize(f);
            }
        }
        return size;
    }

    /**
     * Opens the system file picker to select one or more files for import.
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        filePickerLauncher.launch(intent);
    }

    /**
     * Opens the system folder picker to select a folder for import.
     */
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    /**
     * Handles clicks on a file or folder in the list, showing available actions.
     */
    private void onFileOrFolderClicked(File file) {
        fileToExportOrDelete = file;
        isExportingFolder = file.isDirectory();
        showFileActionsDialog(file);
    }

    /**
     * Displays a dialog with actions for a selected file (Copy, Rename, Delete).
     */
    private void showFileActionsDialog(File file) {
        String[] options = {"Copy", "Rename", "Delete"};
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(options, (dialog, which) -> {
            if (which == 0) { // Copy (Export)
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                deviceFolderPickerLauncher.launch(intent);
            } else if (which == 1) { // Rename
                showRenameDialog(file);
            } else if (which == 2) { // Delete
                showDeleteConfirmDialog(file);
            }
        }).show();
    }

    /**
     * Shows a dialog to get a new name for a file or folder.
     */
    private void showRenameDialog(File file) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), 0);

        final TextView label = new TextView(this);
        label.setText("New name:");
        layout.addView(label);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(file.getName());
        // Pre-select the name without the extension for easier renaming.
        int dotIdx = file.getName().lastIndexOf('.');
        if (dotIdx > 0) {
            input.setSelection(0, dotIdx);
        } else {
            input.setSelection(0, file.getName().length());
        }
        layout.addView(input);

        new AlertDialog.Builder(this).setTitle("Rename").setView(layout).setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty() || newName.equals(file.getName())) {
                if (!isFinishing()) Toast.makeText(this, "Invalid or unchanged name.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (containsInvalidFileNameChars(newName)) {
                if (!isFinishing()) Toast.makeText(this, "Invalid characters in name.", Toast.LENGTH_SHORT).show();
                return;
            }
            File newFile = new File(file.getParentFile(), newName);
            if (newFile.exists()) {
                if (!isFinishing()) Toast.makeText(this, "A file or folder with that name already exists.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean success = file.renameTo(newFile);
            if (success) {
                if (!isFinishing()) Toast.makeText(this, "Renamed.", Toast.LENGTH_SHORT).show();
            } else {
                if (!isFinishing()) Toast.makeText(this, "Rename failed!", Toast.LENGTH_SHORT).show();
            }
            refreshFileList();
        }).setNegativeButton("Cancel", null).show();
    }

    /**
     * Shows a confirmation dialog before deleting a file or folder.
     */
    private void showDeleteConfirmDialog(File file) {
        new AlertDialog.Builder(this).setTitle("Delete").setMessage("Are you sure you want to delete \"" + file.getName() + "\"?").setPositiveButton("Delete", (dialog, which) -> {
            boolean deleted = deleteRecursively(file);
            if (deleted) {
                if (!isFinishing()) Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
            } else {
                if (!isFinishing()) Toast.makeText(this, "Delete failed!", Toast.LENGTH_SHORT).show();
            }
            refreshFileList();
        }).setNegativeButton("Cancel", null).show();
    }

    /**
     * Displays a simple dialog to show the result of an operation (e.g., success, failure, cancellation).
     */
    private void showOperationResultDialog(String title, String message) {
        if (!isFinishing()) {
            new AlertDialog.Builder(this).setTitle(title).setMessage(message).setCancelable(true).setPositiveButton("OK", null).show();
        }
    }

    /**
     * Shows a dialog for name conflicts during import operations.
     * 
     * @param name The name of the file or folder that caused the conflict
     */
    private void showImportNameConflictDialog(String name) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this)
                    .setTitle("Name Conflict")
                    .setMessage("A file or folder named \"" + name + "\" already exists in the app documents directory. Operation aborted.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    /**
     * Shows a dialog for name conflicts during export operations.
     * 
     * @param name The name of the file or folder that caused the conflict
     */
    private void showExportNameConflictDialog(String name) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this)
                    .setTitle("Name Conflict")
                    .setMessage("A file or folder named \"" + name + "\" already exists at the destination. Operation aborted.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    /**
     * Shows a dialog for general file operation errors.
     * 
     * @param operation The operation name (e.g., "Import", "Export")
     * @param message The error message
     */
    private void showFileOperationErrorDialog(String operation, String message) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this)
                    .setTitle(operation + " Failed")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    /**
     * Starts the file import process on a background thread with a progress dialog.
     * Implements atomic copy: file is copied to a temporary name and renamed on success.
     */
    private void startCopyFileWithProgress(List<Uri> fileUris) {
        showProgressDialog("Copying file(s)...");
        
        final WeakReference<FileManagerActivity> activityRef = new WeakReference<>(this);
        
        fileViewModel.execute(() -> {
            final boolean[] error = {false};
            final String[] errorMessage = {null};
            final String[] lastFileName = {null}; // Track the last file name being processed
            
            for (Uri fileUri : fileUris) {
                if (fileViewModel.isCancelRequested()) break;
                
                final String fileName = queryFileName(fileUri);
                lastFileName[0] = fileName; // Store for error handling
                final File tempDestFile = new File(appDocumentsDir, "." + fileName + ".copying");
                final File finalDestFile = new File(appDocumentsDir, fileName);
                
                if (finalDestFile.exists() || tempDestFile.exists()) {
                    error[0] = true;
                    errorMessage[0] = fileName; // Store just the name
                    // Don't show dialog here, will be shown at the end
                    break;
                }
                
                // Check storage space before copying
                try {
                    long fileSize = getFileSizeFromUri(fileUri);
                    if (fileSize > 0 && !hasEnoughStorageSpace(fileSize)) {
                        error[0] = true;
                        errorMessage[0] = "Not enough storage space. Need at least " + 
                            formatFileSize(fileSize + STORAGE_SAFETY_MARGIN_BYTES);
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not determine file size, continuing anyway", e);
                }
                
                boolean ok = copyFile(fileUri, tempDestFile);
                boolean atomicSuccess = false;
                if (ok && !fileViewModel.isCancelRequested()) {
                    // Rename to final name only if copy was successful.
                    atomicSuccess = tempDestFile.renameTo(finalDestFile);
                    if (!atomicSuccess) {
                        deleteRecursively(tempDestFile); // Clean up if rename fails.
                        errorMessage[0] = "Failed to finalize file copy";
                    }
                } else {
                    deleteRecursively(tempDestFile); // Clean up if copy fails or is cancelled.
                    if (fileViewModel.isCancelRequested()) {
                        errorMessage[0] = null; // Don't show error dialog for cancellation
                    } else if (errorMessage[0] == null || !errorMessage[0].equals("Failed to finalize file copy")) {
                        errorMessage[0] = "File copy failed";
                    }
                }
                if (!ok || !atomicSuccess) {
                    error[0] = true;
                    break;
                }
            }
            
            // Update UI on the main thread using WeakReference
            FileManagerActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    activity.dismissProgressDialog();
                    activity.refreshFileList();
                    if (fileViewModel.isCancelRequested()) {
                        activity.showOperationResultDialog("Copy File(s)", "Operation canceled.");
                    } else if (!error[0]) {
                        activity.showOperationResultDialog("Copy File(s)", "File(s) copied successfully!");
                    } else if (errorMessage[0] != null) {
                        // Check if it's a name conflict by comparing with the last file name
                        if (errorMessage[0].equals(lastFileName[0])) {
                            activity.showImportNameConflictDialog(errorMessage[0]);
                        } else {
                            activity.showOperationResultDialog("Copy File(s)", "Failed: " + errorMessage[0]);
                        }
                    }
                });
            }
        });
    }

    /**
     * Gets the file size from a content URI.
     * 
     * @param uri Content URI
     * @return File size in bytes, or -1 if unknown
     */
    private long getFileSizeFromUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get file size from URI", e);
        }
        return -1;
    }

    /**
     * Formats file size for human-readable display.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Starts the folder import process on a background thread with a progress dialog.
     * Implements atomic copy: folder is copied to a temporary name and renamed on success.
     */
    private void startCopyFolderWithProgress(Uri treeUri) {
        showProgressDialog("Copying folder...");
        
        final WeakReference<FileManagerActivity> activityRef = new WeakReference<>(this);
        
        fileViewModel.execute(() -> {
            String folderName = queryDisplayNameFromTreeUri(treeUri);
            if (folderName == null) folderName = "ImportedFolder";
            final File tempRootFolder = new File(appDocumentsDir, "." + folderName + ".copying");
            final File finalRootFolder = new File(appDocumentsDir, folderName);
            
            if (finalRootFolder.exists() || tempRootFolder.exists()) {
                FileManagerActivity activity = activityRef.get();
                if (activity != null) {
                    activity.showImportNameConflictDialog(folderName);
                    activity.runOnUiThread(() -> { 
                        if (activity != null && !activity.isFinishing()) 
                            activity.dismissProgressDialog(); 
                    });
                }
                return;
            }
            if (!tempRootFolder.mkdirs()) {
                FileManagerActivity activity = activityRef.get();
                if (activity != null) {
                    activity.showFileOperationErrorDialog("Import", "Failed to create folder \"" + folderName + "\".");
                    activity.runOnUiThread(() -> { 
                        if (activity != null && !activity.isFinishing()) 
                            activity.dismissProgressDialog(); 
                    });
                }
                return;
            }
            
            // Recursively copy the folder contents.
            boolean ok = copyFolder(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), tempRootFolder);
            boolean atomicSuccess = false;
            if (ok && !fileViewModel.isCancelRequested()) {
                // Rename to final name only if copy was successful.
                atomicSuccess = tempRootFolder.renameTo(finalRootFolder);
                if (!atomicSuccess) {
                    deleteRecursively(tempRootFolder);
                }
            } else {
                deleteRecursively(tempRootFolder); // Clean up on failure or cancellation.
            }
            final boolean resultOk = ok;
            final boolean resultAtomic = atomicSuccess;
            
            // Update UI on the main thread using WeakReference
            FileManagerActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    activity.dismissProgressDialog();
                    activity.refreshFileList();
                    if (fileViewModel.isCancelRequested())
                        activity.showOperationResultDialog("Copy Folder", "Operation canceled.");
                    else if (!resultOk || !resultAtomic)
                        activity.showOperationResultDialog("Copy Folder", "Folder copy failed!");
                    else activity.showOperationResultDialog("Copy Folder", "Folder copied successfully!");
                });
            }
        });
    }

    /**
     * Iteratively copies a folder from a SAF URI to a local destination directory.
     */
    private boolean copyFolder(Uri rootTreeUri, File destDir) {
        Stack<Pair<Uri, File>> stack = new Stack<>();
        stack.push(new Pair<>(rootTreeUri, destDir));

        try {
            while (!stack.isEmpty() && !fileViewModel.isCancelRequested()) {
                Pair<Uri, File> current = stack.pop();
                Uri treeUri = current.first;
                File currentDestDir = current.second;

                final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(treeUri));
                try (Cursor cursor = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    while (cursor != null && cursor.moveToNext() && !fileViewModel.isCancelRequested()) {
                        final String childDocId = cursor.getString(0);
                        final String name = cursor.getString(1);
                        final String mimeType = cursor.getString(2);
                        final Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId);

                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                            final File subDir = new File(currentDestDir, name);
                            if (subDir.exists()) {
                                showImportNameConflictDialog(name);
                                return false;
                            }
                            if (!subDir.mkdirs()) {
                                showFileOperationErrorDialog("Import", "Failed to create subfolder \"" + name + "\".");
                                return false;
                            }
                            stack.push(new Pair<>(childUri, subDir));
                        } else {
                            final File destFile = new File(currentDestDir, name);
                            if (!copyFile(childUri, destFile)) {
                                return false;
                            }
                        }
                    }
                }
            }
            return !fileViewModel.isCancelRequested();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy folder", e);
            runOnUiThread(() -> {
                if (!isFinishing()) showOperationResultDialog("Copy Folder", "Folder copy failed: " + e.getMessage());
            });
            return false;
        }
    }

    /**
     * Copies a single file's content from a source URI to a destination File.
     */
    private boolean copyFile(Uri fileUri, File destFile) {
        try (InputStream is = getContentResolver().openInputStream(fileUri)) {
            if (is == null) {
                Log.e(TAG, "Failed to open input stream for: " + fileUri);
                return false;
            }
            try (OutputStream os = new FileOutputStream(destFile)) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    if (fileViewModel.isCancelRequested()) throw new IOException("Cancelled");
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy file: " + fileUri, e);
            runOnUiThread(() -> {
                if (!isFinishing()) showOperationResultDialog("Copy File", "File copy failed: " + e.getMessage());
            });
            return false;
        }
        return true;
    }

    /**
     * Starts the file export process to a user-selected SAF folder.
     */
    private void startCopyFileToDeviceFolderWithProgress(File file, Uri treeUri) {
        showProgressDialog("Exporting file...");
        
        final WeakReference<FileManagerActivity> activityRef = new WeakReference<>(this);
        
        fileViewModel.execute(() -> {
            final String fileName = file.getName();
            
            long fileSize = getFileOrFolderSize(file);
            
            // Check for name collision at the destination before copying.
            final boolean exists = checkNameExistsInDeviceFolder(fileName, treeUri);
            if (exists) {
                FileManagerActivity activity = activityRef.get();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing()) return;
                        activity.dismissProgressDialog();
                        activity.showExportNameConflictDialog(fileName);
                    });
                }
                return;
            }
            final boolean result = copyFileIntoDeviceFolder(file, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));
            
            FileManagerActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    activity.dismissProgressDialog();
                    if (fileViewModel.isCancelRequested())
                        activity.showOperationResultDialog("Export File", "Operation canceled.");
                    else if (!result) activity.showOperationResultDialog("Export File", "Export failed!");
                    else activity.showOperationResultDialog("Export File", "File exported successfully!");
                });
            }
        });
    }

    /**
     * Starts the folder export process to a user-selected SAF folder.
     */
    private void startCopyFolderToDeviceFolderWithProgress(File folder, Uri treeUri) {
        showProgressDialog("Exporting folder...");
        
        final WeakReference<FileManagerActivity> activityRef = new WeakReference<>(this);
        
        fileViewModel.execute(() -> {
            final String folderName = folder.getName();
            // Check for name collision at the destination before copying.
            final boolean exists = checkNameExistsInDeviceFolder(folderName, treeUri);
            if (exists) {
                FileManagerActivity activity = activityRef.get();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing()) return;
                        activity.dismissProgressDialog();
                        activity.showExportNameConflictDialog(folderName);
                    });
                }
                return;
            }
            final boolean result = copyFolderIntoDeviceFolder(folder, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));
            
            FileManagerActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    activity.dismissProgressDialog();
                    if (fileViewModel.isCancelRequested())
                        activity.showOperationResultDialog("Export Folder", "Operation canceled.");
                    else if (!result) activity.showOperationResultDialog("Export Folder", "Export failed!");
                    else activity.showOperationResultDialog("Export Folder", "Folder exported successfully!");
                });
            }
        });
    }

    /**
     * Recursively copies a local folder and its contents to a destination SAF folder URI.
     */
    private boolean copyFolderIntoDeviceFolder(File folder, Uri treeUri) {
        if (fileViewModel.isCancelRequested()) return false;
        final String folderName = folder.getName();
        try {
            // Create the new directory in the destination SAF folder.
            final Uri folderUri = DocumentsContract.createDocument(getContentResolver(), treeUri, DocumentsContract.Document.MIME_TYPE_DIR, folderName);
            if (folderUri == null) {
                Log.e(TAG, "Failed to create folder: " + folderName);
                runOnUiThread(() -> showOperationResultDialog("Export Folder", "Failed to create folder: " + folderName));
                return false;
            }
            final File[] children = folder.listFiles();
            if (children == null) return true; // Empty folder is successfully copied.
            for (File child : children) {
                if (fileViewModel.isCancelRequested()) break;
                if (child.isDirectory()) {
                    // Recurse for subdirectories.
                    final boolean ok = copyFolderIntoDeviceFolder(child, folderUri);
                    if (!ok || fileViewModel.isCancelRequested()) return false;
                } else {
                    // Copy files.
                    if (!copyFileIntoDeviceFolder(child, folderUri)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy folder: " + folderName, e);
            runOnUiThread(() -> showOperationResultDialog("Export Folder", "Export failed: " + e.getMessage()));
            return false;
        }
    }

    /**
     * Copies a single local file to a destination SAF folder URI.
     */
    private boolean copyFileIntoDeviceFolder(File file, Uri treeUri) {
        final String fileName = file.getName();
        final String mimeType = getMimeType(fileName);
        try {
            final ContentResolver cr = getContentResolver();
            // Create the document in the destination SAF folder.
            final Uri docUri = DocumentsContract.createDocument(cr, treeUri, mimeType, fileName);
            if (docUri == null) {
                Log.e(TAG, "Failed to create document: " + fileName);
                throw new IOException("Failed to create document at destination");
            }
            // Stream the local file's content to the new SAF document.
            try (InputStream is = new FileInputStream(file); OutputStream os = cr.openOutputStream(docUri)) {
                if (os == null) throw new IOException("Failed to open output stream to file");
                final byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    if (fileViewModel.isCancelRequested()) throw new IOException("Cancelled");
                    os.write(buffer, 0, len);
                }
                os.flush();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy file: " + fileName, e);
            runOnUiThread(() -> showOperationResultDialog("Export File", "Export failed: " + e.getMessage()));
            return false;
        }
    }

    /**
     * Shows an indeterminate horizontal progress bar for long operations.
     */
    private void showProgressDialog(String message) {
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(message).setView(progressBar).setCancelable(false).setNegativeButton("Cancel", (dialog, which) -> fileViewModel.setCancelRequested(true));
        progressDialog = builder.create();
        if (!isFinishing()) {
            progressDialog.show();
        }
    }

    /**
     * Dismisses the progress dialog if it is showing.
     */
    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            if (!isFinishing()) {
                progressDialog.dismiss();
            }
        }
    }

    /**
     * Shows a fatal error dialog and closes the activity.
     */
    private void showErrorAndExit() {
        if (!isFinishing()) {
            new AlertDialog.Builder(this).setTitle("Error").setMessage("Failed to create app-specific Documents directory.").setCancelable(false).setPositiveButton("OK", (dialog, which) -> finish()).show();
        }
    }

    /**
     * Cleans up any leftover ".copying" artifacts in the app documents directory.
     */
    private void cleanUpCopyingArtifacts(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(".copying")) {
                Log.i(TAG, "Cleaning up copying artifact: " + file.getName());
                deleteRecursively(file);
            }
        }
    }

    /**
     * Deletes a file, or a directory and all its contents.
     */
    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File child : files) deleteRecursively(child);
        }
        boolean result = file.delete();
        if (!result) {
            Log.w(TAG, "Failed to delete: " + file.getAbsolutePath());
        }
        return result;
    }

    /**
     * Checks if a file or folder with the given name exists in a SAF folder URI.
     */
    private boolean checkNameExistsInDeviceFolder(String name, Uri treeUri) {
        if (treeUri == null) return false;
        ContentResolver cr = getContentResolver();
        Uri folderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(folderDocUri, DocumentsContract.getDocumentId(folderDocUri)), new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String childName = c.getString(0);
                    if (name.equals(childName)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking if name exists in device folder", e);
            // Ignore exceptions, e.g., if the folder is no longer accessible.
        }
        return false;
    }

    /**
     * Queries the display name of a folder from its SAF tree URI.
     */
    private String queryDisplayNameFromTreeUri(Uri treeUri) {
        String displayName = null;
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = getContentResolver().query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query display name from tree URI", e);
        }
        return displayName;
    }

    /**
     * Queries the display name of a file from its content URI.
     */
    private String queryFileName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) result = cursor.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query file name", e);
        }
        // Fallback if name can't be queried.
        if (result == null) result = "file_" + System.currentTimeMillis();
        return result;
    }

    /**
     * Checks for invalid characters in a file name.
     */
    private boolean containsInvalidFileNameChars(String name) {
        return name.contains("/") || name.contains("\0");
    }

    /**
     * Determines a MIME type based on a file's extension.
     */
    private String getMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("txt")) return "text/plain";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("png")) return "image/png";
        return "application/octet-stream"; // Default binary stream type.
    }

    /**
     * Converts density-independent pixels (dp) to pixels (px).
     */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Reloads the list of files from the app's documents directory and refreshes the UI.
     */
    private void refreshFileList() {
        fileList.clear();
        File[] files = appDocumentsDir.listFiles();
        if (files != null) fileList.addAll(Arrays.asList(files));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // Helper class for iterative folder copying
    private static class Pair<F, S> {
        final F first;
        final S second;

        Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }
    }

    /**
     * RecyclerView Adapter for displaying the list of files and folders.
     */
    static class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.VH> {
        interface OnClickListener {
            void onClick(File file);
        }

        private final List<File> files;
        private OnClickListener listener;

        FileListAdapter(List<File> files) {
            this.files = files;
        }

        public void setOnItemClickListener(OnClickListener listener) {
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Programmatically create the list item view.
            LinearLayout layout = new LinearLayout(parent.getContext());
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setBackgroundResource(android.R.drawable.list_selector_background);

            layout.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView icon = new TextView(parent.getContext());
            icon.setPadding(24, 24, 10, 24);
            icon.setTextSize(22f);
            icon.setTextColor(Color.parseColor("#1976D2"));

            TextView tv = new TextView(parent.getContext());
            tv.setPadding(0, 24, 24, 24);
            tv.setTextSize(18f);

            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(textParams);

            layout.addView(icon);
            layout.addView(tv);

            layout.setClickable(true);
            layout.setFocusable(true);

            return new VH(layout, icon, tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            File file = files.get(position);
            // Set different icons for folders and files.
            if (file.isDirectory()) {
                holder.iconView.setText("\uD83D\uDCC2"); // 📂
                holder.iconView.setTextColor(Color.parseColor("#1976D2"));
            } else {
                holder.iconView.setText("\uD83D\uDCC3"); // 📃
                holder.iconView.setTextColor(Color.parseColor("#616161"));
            }
            holder.textView.setText(file.getName());
            holder.itemView.setClickable(true);
            holder.itemView.setFocusable(true);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(file);
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        /**
         * ViewHolder for the file list item.
         */
        static class VH extends RecyclerView.ViewHolder {
            TextView iconView;
            TextView textView;

            VH(@NonNull View itemView, TextView iconView, TextView textView) {
                super(itemView);
                this.iconView = iconView;
                this.textView = textView;
            }
        }
    }
}
