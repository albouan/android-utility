package com.ngcomputing.fora.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h2>FileManagerActivity</h2>
 * <p>
 * A simple file manager Activity for Android apps, designed for safe
 * user-driven import and export of files and folders between the Android
 * Storage Access Framework (SAF) and an app-private "Documents" directory.
 *
 * <h2>Version</h2>
 * <ul>
 *   <li>2.0 (2026-01-12)</li>
 *   <li>1.1 (2025-05-29)</li>
 *   <li>1.0 (2025-05-27)</li>
 * </ul>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Import files or entire folders from user-picked SAF locations into the
 * app's documents directory</li> 
 *   <li>Export files or folders from the app's documents directory to any 
 * user-picked SAF folder</li> 
 *   <li>Rename and delete files/folders in the app's documents directory</li> 
 *   <li>Multi-file selection support for imports</li> 
 *   <li>No permissions required: all file access is performed via SAF or 
 * app-private storage</li>
 * </ul>
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>URIs returned by the Storage Access Framework (SAF)
 * are treated as transient in this implementation. This activity does not call
 * {@code takePersistableUriPermission(...)} or persist granted URI permissions
 * across restarts.</li>
 *   <li>Modifications to the device folder are strictly non-destructive by design:
 * exports use the Storage Access Framework, name collisions are detected and the
 * operation is stopped, and the activity does not delete or overwrite existing
 * files in external/device folders.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <ul>
 *   <li>Integrate as a standalone Activity in your Android application to give
 * users a simple, safe file manager UI</li> 
 *   <li>UI is built programmatically; no XML required</li> 
 *   <li>All user actions are confirmed via dialogs</li>
 *   <li>Can be extended or customized as needed for your application's
 * workflow</li>
 * </ul>
 *
 * <h2>Author</h2>
 * <ul>
 *   <li>2025-2026, GitHub Copilot for @albouan</li>
 * </ul>
 *
 * <h2>License</h2>
 * <ul>
 *   <li>LGPL - GNU Lesser General Public License</li>
 * </ul>
 */
@SuppressLint({"NotifyDataSetChanged", "SetTextI18n"})
public class FileManagerActivity extends AppCompatActivity {
    private static final int BUFFER_SIZE = 65536;

    private File appDocumentsDir;
    private FileListAdapter adapter;
    private final List<File> fileList = new ArrayList<>();
    private File fileToExportOrDelete = null;
    private boolean isExportingFolder = false;

    private AlertDialog progressDialog;
    private volatile boolean cancelRequested = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> deviceFolderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir == null) {
            showErrorAndExit();
            return;
        }
        appDocumentsDir = new File(externalFilesDir, "Documents");
        if ((!appDocumentsDir.exists() && !appDocumentsDir.mkdirs()) || !appDocumentsDir.isDirectory()) {
            showErrorAndExit();
            return;
        }

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
            if (treeUri != null) {
                startCopyFolderWithProgress(treeUri);
            }
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

        Window window = getWindow();

        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(window, window.getDecorView());
        wic.setAppearanceLightStatusBars(true);
        wic.setAppearanceLightNavigationBars(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(24) + systemBars.left, dp(24) + systemBars.top, dp(24) + systemBars.right, dp(24) + systemBars.bottom);
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

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshFileList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelRequested = true;
        executor.shutdownNow();
        dismissProgressDialog();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void openDeviceFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        deviceFolderPickerLauncher.launch(intent);
    }

    private void onFileOrFolderClicked(File file) {
        fileToExportOrDelete = file;
        isExportingFolder = file.isDirectory();
        String[] options = {"Copy", "Rename", "Delete"};
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(options, (dialog, which) -> {
            if (which == 0) {
                openDeviceFolderPicker();
            } else if (which == 1) {
                final LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(dp(24), dp(12), dp(24), 0);

                final TextView label = new TextView(this);
                label.setText("New name:");
                layout.addView(label);

                final EditText input = new EditText(this);
                input.setText(file.getName());
                int dotIdx = file.getName().lastIndexOf('.');
                if (dotIdx > 0) {
                    input.setSelection(0, dotIdx);
                } else {
                    input.setSelection(0, file.getName().length());
                }
                layout.addView(input);

                new AlertDialog.Builder(this).setTitle("Rename").setView(layout).setPositiveButton("Rename", (dialog1, which1) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(file.getName())) {
                        showResultDialog("Rename", "Invalid or unchanged name.");
                        return;
                    }
                    if (newName.contains("/") || newName.contains("\0")) {
                        showResultDialog("Rename", "Invalid characters in name.");
                        return;
                    }
                    File newFile = new File(file.getParentFile(), newName);
                    if (newFile.exists()) {
                        showResultDialog("Rename", "A file or folder with that name already exists.");
                        return;
                    }
                    boolean success = file.renameTo(newFile);
                    if (success) {
                        showResultDialog("Rename", "Item was renamed successfully.");
                    } else {
                        showResultDialog("Rename", "Rename failed!");
                    }
                    refreshFileList();
                }).setNegativeButton("Cancel", null).show();
            } else if (which == 2) {
                new AlertDialog.Builder(this).setTitle("Delete").setMessage("Are you sure you want to delete \"" + file.getName() + "\"?").setPositiveButton("Delete", (dialog1, which1) -> {
                    boolean deleted = deleteRecursively(file);
                    if (deleted) {
                        showResultDialog("Delete", "Item was deleted successfully.");
                    } else {
                        showResultDialog("Delete", "Delete failed!");
                    }
                    refreshFileList();
                }).setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private void startCopyFileWithProgress(List<Uri> fileUris) {
        showProgressDialog("Copying file(s)...");
        executor.execute(() -> {
            for (Uri fileUri : fileUris) {
                final String fileName = queryFileName(fileUri);
                final File destFile = new File(appDocumentsDir, fileName);
                if (destFile.exists()) {
                    dismissProgressDialog(() -> showFileExistsError(fileName));
                    return;
                }
            }

            final boolean[] ok = {true};
            for (Uri fileUri : fileUris) {
                if (!ok[0] || cancelRequested) break;
                final String fileName = queryFileName(fileUri);
                final File destFile = new File(appDocumentsDir, fileName);
                ok[0] = copyFile(fileUri, destFile);
            }

            if (!ok[0] || cancelRequested) {
                for (Uri fileUri : fileUris) {
                    final String fileName = queryFileName(fileUri);
                    final File destFile = new File(appDocumentsDir, fileName);
                    if (destFile.exists()) {
                        deleteRecursively(destFile);
                    }
                }
            }

            dismissProgressDialog(() -> {
                refreshFileList();
                if (cancelRequested) {
                    showResultDialog("Copy File(s)", "The copy operation was canceled.");
                } else {
                    if (ok[0]) {
                        showResultDialog("Copy File(s)", "File(s) copied successfully!");
                    } else {
                        showResultDialog("Copy File(s)", "File(s) copy failed!");
                    }
                }
            });
        });
    }

    private void startCopyFolderWithProgress(Uri treeUri) {
        showProgressDialog("Copying folder...");
        executor.execute(() -> {
            String folderName = queryDisplayNameFromTreeUri(treeUri);
            if (folderName == null) folderName = "ImportedFolder";
            final File destFolder = new File(appDocumentsDir, folderName);

            if (destFolder.exists()) {
                final String name = folderName;
                dismissProgressDialog(() -> showFileExistsError(name));
                return;
            }

            if (!destFolder.mkdirs()) {
                dismissProgressDialog(() -> showResultDialog("Error", "Failed to create folder."));
                return;
            }

            final boolean ok = copyFolder(treeUri, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), destFolder);

            if (!ok || cancelRequested) {
                deleteRecursively(destFolder);
            }

            dismissProgressDialog(() -> {
                refreshFileList();
                if (cancelRequested) {
                    showResultDialog("Copy Folder", "The copy operation was canceled.");
                } else {
                    if (ok) {
                        showResultDialog("Copy Folder", "Folder copied successfully!");
                    } else {
                        showResultDialog("Copy Folder", "Folder copy failed!");
                    }
                }
            });
        });
    }

    private boolean copyFolder(Uri treeUri, Uri documentUri, File destDir) {
        try {
            final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(documentUri));
            try (Cursor cursor = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (cursor != null && cursor.moveToNext() && !cancelRequested) {
                    final String childDocId = cursor.getString(0);
                    final String name = cursor.getString(1);
                    final String mimeType = cursor.getString(2);
                    final Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        final File subDir = new File(destDir, name);
                        if (subDir.exists() || !subDir.mkdirs()) {
                            return false;
                        }
                        final boolean ok = copyFolder(treeUri, childUri, subDir);
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
            return false;
        }
    }

    private boolean copyFile(Uri fileUri, File destFile) {
        try (InputStream is = getContentResolver().openInputStream(fileUri)) {
            if (is == null) return false;
            try (OutputStream os = new FileOutputStream(destFile)) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    if (cancelRequested) return false;
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private void startCopyFileToDeviceFolderWithProgress(File file, Uri treeUri) {
        showProgressDialog("Exporting file...");
        executor.execute(() -> {
            final String fileName = file.getName();
            final boolean exists = checkNameExistsInDeviceFolder(fileName, treeUri);
            if (exists) {
                dismissProgressDialog(() -> showFileExistsError(fileName));
                return;
            }

            final boolean ok = copyFileIntoDeviceFolder(file, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));

            dismissProgressDialog(() -> {
                if (cancelRequested) {
                    showResultDialog("Export File", "The export operation was canceled.");
                } else {
                    if (ok) {
                        showResultDialog("Export File", "File exported successfully!");
                    } else {
                        showResultDialog("Export File", "Export failed!");
                    }
                }
            });
        });
    }

    private void startCopyFolderToDeviceFolderWithProgress(File folder, Uri treeUri) {
        showProgressDialog("Exporting folder...");
        executor.execute(() -> {
            final String folderName = folder.getName();
            final boolean exists = checkNameExistsInDeviceFolder(folderName, treeUri);
            if (exists) {
                dismissProgressDialog(() -> showFileExistsError(folderName));
                return;
            }

            final boolean ok = copyFolderIntoDeviceFolder(folder, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));

            dismissProgressDialog(() -> {
                if (cancelRequested) {
                    showResultDialog("Export Folder", "The export operation was canceled.");
                } else {
                    if (ok) {
                        showResultDialog("Export Folder", "Folder exported successfully!");
                    } else {
                        showResultDialog("Export Folder", "Export failed!");
                    }
                }
            });
        });
    }

    private boolean copyFolderIntoDeviceFolder(File folder, Uri documentUri) {
        if (cancelRequested) return false;
        final String folderName = folder.getName();
        try {
            final Uri folderUri = DocumentsContract.createDocument(getContentResolver(), documentUri, DocumentsContract.Document.MIME_TYPE_DIR, folderName);
            if (folderUri == null) {
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
            return false;
        }
    }

    private boolean copyFileIntoDeviceFolder(File file, Uri documentUri) {
        if (cancelRequested) return false;
        final String fileName = file.getName();
        final String mimeType = getMimeType(fileName);
        try {
            final ContentResolver cr = getContentResolver();
            final Uri docUri = DocumentsContract.createDocument(cr, documentUri, mimeType, fileName);
            if (docUri == null) throw new IOException("Failed to create document at destination");
            try (InputStream is = new FileInputStream(file); OutputStream os = cr.openOutputStream(docUri)) {
                if (os == null) throw new IOException("Failed to open output stream to file");
                final byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
                os.flush();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void showResultDialog(String title, String message) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this).setTitle(title).setMessage(message).setCancelable(true).setPositiveButton("OK", null).create().show();
            }
        });
    }

    private void showProgressDialog(String message) {
        runOnUiThread(() -> {
            cancelRequested = false;

            ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(true);

            AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(message).setView(progressBar).setCancelable(false).setNegativeButton("Cancel", (dialog, which) -> cancelRequested = true);
            progressDialog = builder.create();

            if (!isFinishing()) {
                progressDialog.show();
            }
        });
    }

    private void dismissProgressDialog() {
        dismissProgressDialog(null);
    }

    private void dismissProgressDialog(Runnable postDismiss) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
            }
            if (postDismiss != null) {
                postDismiss.run();
            }
        });
    }

    private void showErrorAndExit() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this).setTitle("Error").setMessage("Failed to create app-specific Documents directory.").setCancelable(false).setPositiveButton("OK", (dialog, which) -> finish()).show();
            }
        });
    }

    private void showFileExistsError(String name) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                new AlertDialog.Builder(this).setTitle("File or Folder exists").setMessage("A file or folder named \"" + name + "\" already exists in the destination. Operation stopped.").setCancelable(false).setPositiveButton("OK", null).show();
            }
        });
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
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (name.equals(c.getString(0))) {
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
        final Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = getContentResolver().query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return null;
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

    private String getMimeType(String fileName) {
        String type = null;
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.isEmpty()) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return type != null ? type : "application/octet-stream";
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void refreshFileList() {
        fileList.clear();
        File[] files = appDocumentsDir.listFiles();
        if (files != null) fileList.addAll(Arrays.asList(files));

        fileList.sort(new Comparator<>() {
            public int compare(File f1, File f2) {
                boolean d1 = f1.isDirectory();
                boolean d2 = f2.isDirectory();
                if (d1 != d2) return d1 ? -1 : 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });

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
            icon.setPadding(dp(16), dp(16), dp(8), dp(16));
            icon.setTextSize(22f);
            icon.setTextColor(Color.parseColor("#1976D2"));

            TextView tv = new TextView(parent.getContext());
            tv.setPadding(0, dp(16), dp(16), dp(16));
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

        static int dp(int dp) {
            return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
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
