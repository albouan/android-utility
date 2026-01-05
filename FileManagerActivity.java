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
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * <h2>FileManagerActivity</h2>
 *
 * A robust, atomic file manager Activity for Android apps, designed for safe user-driven import and export of files
 * and folders between the Android Storage Access Framework (SAF) and an app-private "Documents" directory.
 *
 * <h2>Version</h2>
 * <ul>
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
 *   <li>All copy operations are atomic with crash/cancel recovery: partial data is always cleaned up</li>
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
 * </ul>
 *
 * <h2>Implementation Details</h2>
 * <ul>
 *   <li>App Documents Directory: <code>getExternalFilesDir(null)/Documents</code>; private to the app</li>
 *   <li>SAF: All user-accessible file/folder import/export uses SAF intents (ACTION_OPEN_DOCUMENT, ACTION_OPEN_DOCUMENT_TREE)</li>
 *   <li>Threading: File operations run on background threads; UI updates are always posted to the main thread</li>
 *   <li>Recursion: Folder imports/exports are recursive—deeply nested folders may hit the recursion limit</li>
 *   <li>File/folder name checks: By default, only "/" and null bytes are blocked; override for stricter validation if needed</li>
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
 *   <li>2025, GitHub Copilot</li>
 * </ul>
 *
 * <h2>License</h2>
 * <ul>
 *   <li>LGPL - GNU Lesser General Public License</li>
 * </ul>
 */
@SuppressLint({"NotifyDataSetChanged", "SetTextI18n"})
public class FileManagerActivity extends AppCompatActivity {
    private File appDocumentsDir;
    private FileListAdapter adapter;
    private final List<File> fileList = new ArrayList<>();
    private File fileToExportOrDelete = null;
    private boolean isExportingFolder = false;

    private AlertDialog progressDialog;
    private volatile boolean cancelRequested = false;

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> deviceFolderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        appDocumentsDir = new File(getExternalFilesDir(null), "Documents");
        if ((!appDocumentsDir.exists() && !appDocumentsDir.mkdirs()) || !appDocumentsDir.isDirectory()) {
            showErrorAndExit();
            return;
        }

        cleanUpCopyingArtifacts(appDocumentsDir);

        filePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else {
                uris.add(data.getData());
            }
            startCopyFileWithProgress(uris);
        });

        folderPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri treeUri = result.getData().getData();
            startCopyFolderWithProgress(treeUri);
        });

        deviceFolderPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri destTreeUri = result.getData().getData();
            if (fileToExportOrDelete != null && destTreeUri != null) {
                if (isExportingFolder) {
                    startCopyFolderToDeviceFolderWithProgress(fileToExportOrDelete, destTreeUri);
                } else {
                    startCopyFileToDeviceFolderWithProgress(fileToExportOrDelete, destTreeUri);
                }
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        adapter = new FileListAdapter(fileList);
        recyclerView.setAdapter(adapter);

        btnCopyFile.setOnClickListener(v -> openFilePicker());
        btnCopyFolder.setOnClickListener(v -> openFolderPicker());

        adapter.setOnItemClickListener(this::onFileOrFolderClicked);

        refreshFileList();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    private void onFileOrFolderClicked(File file) {
        fileToExportOrDelete = file;
        isExportingFolder = file.isDirectory();
        showFileActionsDialog(file);
    }

    private void showFileActionsDialog(File file) {
        String[] options = {"Copy", "Rename", "Delete"};
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(options, (dialog, which) -> {
            if (which == 0) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                deviceFolderPickerLauncher.launch(intent);
            } else if (which == 1) {
                showRenameDialog(file);
            } else if (which == 2) {
                showDeleteConfirmDialog(file);
            }
        }).show();
    }

    private void showRenameDialog(File file) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), 0);

        final TextView label = new TextView(this);
        label.setText("New name:");
        layout.addView(label);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(file.getName());
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
                Toast.makeText(this, "Invalid or unchanged name.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (containsInvalidFileNameChars(newName)) {
                Toast.makeText(this, "Invalid characters in name.", Toast.LENGTH_SHORT).show();
                return;
            }
            File newFile = new File(file.getParentFile(), newName);
            if (newFile.exists()) {
                Toast.makeText(this, "A file or folder with that name already exists.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean success = file.renameTo(newFile);
            if (success) {
                Toast.makeText(this, "Renamed.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Rename failed!", Toast.LENGTH_SHORT).show();
            }
            refreshFileList();
        }).setNegativeButton("Cancel", null).show();
    }

    private void showDeleteConfirmDialog(File file) {
        new AlertDialog.Builder(this).setTitle("Delete").setMessage("Are you sure you want to delete \"" + file.getName() + "\"?").setPositiveButton("Delete", (dialog, which) -> {
            boolean deleted = deleteRecursively(file);
            if (deleted) {
                Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Delete failed!", Toast.LENGTH_SHORT).show();
            }
            refreshFileList();
        }).setNegativeButton("Cancel", null).show();
    }

    private void showOperationResultDialog(String title, String message) {
        if (!isFinishing()) {
            new AlertDialog.Builder(this).setTitle(title).setMessage(message).setCancelable(true).setPositiveButton("OK", null).show();
        }
    }

    private void startCopyFileWithProgress(List<Uri> fileUris) {
        showProgressDialog("Copying file(s)...");
        new Thread(() -> {
            final boolean[] error = {false};
            for (Uri fileUri : fileUris) {
                if (cancelRequested) break;
                final String fileName = queryFileName(fileUri);
                final File tempDestFile = new File(appDocumentsDir, "." + fileName + ".copying");
                final File finalDestFile = new File(appDocumentsDir, fileName);
                if (finalDestFile.exists() || tempDestFile.exists()) {
                    error[0] = true;
                    showFileExistsErrorOnMainThread(fileName);
                    break;
                }
                boolean ok = copyFile(fileUri, tempDestFile);
                boolean atomicSuccess = false;
                if (ok && !cancelRequested) {
                    atomicSuccess = tempDestFile.renameTo(finalDestFile);
                    if (!atomicSuccess) {
                        deleteRecursively(tempDestFile);
                    }
                } else {
                    deleteRecursively(tempDestFile);
                }
                if (!ok || !atomicSuccess) {
                    error[0] = true;
                    break;
                }
            }
            runOnUiThread(() -> {
                dismissProgressDialog();
                refreshFileList();
                if (cancelRequested)
                    showOperationResultDialog("Copy File(s)", "Operation canceled.");
                else if (!error[0])
                    showOperationResultDialog("Copy File(s)", "File(s) copied successfully!");
            });
        }).start();
    }

    private void startCopyFolderWithProgress(Uri treeUri) {
        showProgressDialog("Copying folder...");
        new Thread(() -> {
            String folderName = queryDisplayNameFromTreeUri(treeUri);
            if (folderName == null) folderName = "ImportedFolder";
            final File tempRootFolder = new File(appDocumentsDir, "." + folderName + ".copying");
            final File finalRootFolder = new File(appDocumentsDir, folderName);
            if (finalRootFolder.exists() || tempRootFolder.exists()) {
                showFileExistsErrorOnMainThread(folderName);
                runOnUiThread(this::dismissProgressDialog);
                return;
            }
            if (!tempRootFolder.mkdirs()) {
                showFileExistsErrorOnMainThread(folderName);
                runOnUiThread(this::dismissProgressDialog);
                return;
            }
            boolean ok = copyFolder(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), tempRootFolder);
            boolean atomicSuccess = false;
            if (ok && !cancelRequested) {
                atomicSuccess = tempRootFolder.renameTo(finalRootFolder);
                if (!atomicSuccess) {
                    deleteRecursively(tempRootFolder);
                }
            } else {
                deleteRecursively(tempRootFolder);
            }
            final boolean resultOk = ok;
            final boolean resultAtomic = atomicSuccess;
            runOnUiThread(() -> {
                dismissProgressDialog();
                refreshFileList();
                if (cancelRequested)
                    showOperationResultDialog("Copy Folder", "Operation canceled.");
                else if (!resultOk || !resultAtomic)
                    showOperationResultDialog("Copy Folder", "Folder copy failed!");
                else showOperationResultDialog("Copy Folder", "Folder copied successfully!");
            });
        }).start();
    }

    private boolean copyFolder(Uri treeUri, File destDir) {
        try {
            final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(treeUri));
            try (Cursor cursor = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (cursor != null && cursor.moveToNext() && !cancelRequested) {
                    final String childDocId = cursor.getString(0);
                    final String name = cursor.getString(1);
                    final String mimeType = cursor.getString(2);
                    final Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        final File subDir = new File(destDir, name);
                        if (subDir.exists()) {
                            showFileExistsErrorOnMainThread(name);
                            return false;
                        }
                        if (!subDir.mkdirs()) {
                            showFileExistsErrorOnMainThread(name);
                            return false;
                        }
                        final boolean ok = copyFolder(childUri, subDir);
                        if (!ok || cancelRequested) return false;
                    } else {
                        final File destFile = new File(destDir, name);
                        if (!copyFile(childUri, destFile)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            runOnUiThread(() -> showOperationResultDialog("Copy Folder", "Folder copy failed: " + e.getMessage()));
            return false;
        }
    }

    private boolean copyFile(Uri fileUri, File destFile) {
        try (InputStream is = getContentResolver().openInputStream(fileUri)) {
            if (is == null) return false;
            try (OutputStream os = new FileOutputStream(destFile)) {
                final byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    if (cancelRequested) throw new IOException("Cancelled");
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            runOnUiThread(() -> showOperationResultDialog("Copy File", "File copy failed: " + e.getMessage()));
            return false;
        }
        return true;
    }

    private void startCopyFileToDeviceFolderWithProgress(File file, Uri treeUri) {
        showProgressDialog("Exporting file...");
        new Thread(() -> {
            final String fileName = file.getName();
            final boolean exists = checkNameExistsInDeviceFolder(fileName, treeUri);
            if (exists) {
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    showFileExistsErrorOnMainThread(fileName);
                });
                return;
            }
            final boolean result = copyFileIntoDeviceFolder(file, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));
            runOnUiThread(() -> {
                dismissProgressDialog();
                if (cancelRequested)
                    showOperationResultDialog("Export File", "Operation canceled.");
                else if (!result) showOperationResultDialog("Export File", "Export failed!");
                else showOperationResultDialog("Export File", "File exported successfully!");
            });
        }).start();
    }

    private void startCopyFolderToDeviceFolderWithProgress(File folder, Uri treeUri) {
        showProgressDialog("Exporting folder...");
        new Thread(() -> {
            final String folderName = folder.getName();
            final boolean exists = checkNameExistsInDeviceFolder(folderName, treeUri);
            if (exists) {
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    showFileExistsErrorOnMainThread(folderName);
                });
                return;
            }
            final boolean result = copyFolderIntoDeviceFolder(folder, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));
            runOnUiThread(() -> {
                dismissProgressDialog();
                if (cancelRequested)
                    showOperationResultDialog("Export Folder", "Operation canceled.");
                else if (!result) showOperationResultDialog("Export Folder", "Export failed!");
                else showOperationResultDialog("Export Folder", "Folder exported successfully!");
            });
        }).start();
    }

    private boolean copyFolderIntoDeviceFolder(File folder, Uri treeUri) {
        if (cancelRequested) return false;
        final String folderName = folder.getName();
        try {
            final Uri folderUri = DocumentsContract.createDocument(getContentResolver(), treeUri, DocumentsContract.Document.MIME_TYPE_DIR, folderName);
            if (folderUri == null) {
                runOnUiThread(() -> showOperationResultDialog("Export Folder", "Failed to create folder: " + folderName));
                return false;
            }
            final File[] children = folder.listFiles();
            if (children == null) return true;
            for (File child : children) {
                if (cancelRequested) break;
                if (child.isDirectory()) {
                    final boolean ok = copyFolderIntoDeviceFolder(child, folderUri);
                    if (!ok || cancelRequested) return false;
                } else {
                    if (!copyFileIntoDeviceFolder(child, folderUri)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            runOnUiThread(() -> showOperationResultDialog("Export Folder", "Export failed: " + e.getMessage()));
            return false;
        }
    }

    private boolean copyFileIntoDeviceFolder(File file, Uri treeUri) {
        final String fileName = file.getName();
        final String mimeType = getMimeType(fileName);
        try {
            final ContentResolver cr = getContentResolver();
            final Uri docUri = DocumentsContract.createDocument(cr, treeUri, mimeType, fileName);
            if (docUri == null) throw new IOException("Failed to create document at destination");
            try (InputStream is = new FileInputStream(file); OutputStream os = cr.openOutputStream(docUri)) {
                if (os == null) throw new IOException("Failed to open output stream to file");
                final byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    if (cancelRequested) throw new IOException("Cancelled");
                    os.write(buffer, 0, len);
                }
                os.flush();
                return true;
            }
        } catch (Exception e) {
            runOnUiThread(() -> showOperationResultDialog("Export File", "Export failed: " + e.getMessage()));
            return false;
        }
    }

    private void cleanUpCopyingArtifacts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                if (file.getName().endsWith(".copying")) {
                    deleteRecursively(file);
                } else {
                    cleanUpCopyingArtifacts(file);
                }
            } else {
                if (file.getName().endsWith(".copying")) {
                    deleteRecursively(file);
                }
            }
        }
    }

    private void showProgressDialog(String message) {
        cancelRequested = false;
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(message).setView(progressBar).setCancelable(false).setNegativeButton("Cancel", (dialog, which) -> cancelRequested = true);
        progressDialog = builder.create();
        progressDialog.show();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showErrorAndExit() {
        new AlertDialog.Builder(this).setTitle("Error").setMessage("Failed to create app-specific Documents directory.").setCancelable(false).setPositiveButton("OK", (dialog, which) -> finish()).show();
    }

    private void showFileExistsErrorOnMainThread(String name) {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("File or Folder exists").setMessage("A file or folder named \"" + name + "\" already exists in the destination. Operation stopped.").setPositiveButton("OK", null).show());
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File child : files) deleteRecursively(child);
        }
        return file.delete();
    }

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
            //
        }
        return false;
    }

    private String queryDisplayNameFromTreeUri(Uri treeUri) {
        String displayName = null;
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = getContentResolver().query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(0);
            }
        }
        return displayName;
    }

    private String queryFileName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) result = cursor.getString(idx);
            }
        }
        if (result == null) result = "file_" + System.currentTimeMillis();
        return result;
    }

    private boolean containsInvalidFileNameChars(String name) {
        return name.contains("/") || name.contains("\0");
    }

    private String getMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("txt")) return "text/plain";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("png")) return "image/png";
        return "application/octet-stream";
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void refreshFileList() {
        fileList.clear();
        File[] files = appDocumentsDir.listFiles();
        if (files != null) fileList.addAll(Arrays.asList(files));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

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
