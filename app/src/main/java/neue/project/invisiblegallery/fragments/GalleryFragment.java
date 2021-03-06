package neue.project.invisiblegallery.fragments;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

import neue.project.invisiblegallery.interfaces.EmptyListener;
import neue.project.invisiblegallery.R;
import neue.project.invisiblegallery.adapters.GalleryOverviewAdapter;
import neue.project.invisiblegallery.data.Database;
import neue.project.invisiblegallery.data.Image;
import neue.project.invisiblegallery.data.ImageDao;
import neue.project.invisiblegallery.util.ConstantWidthGridLayoutManager;
import neue.project.invisiblegallery.util.Util;

import static android.app.Activity.RESULT_CANCELED;

public class GalleryFragment extends Fragment implements EmptyListener {
    private static final int IMPORT_IMAGE = 100;
    private static final int TAKE_PHOTO = 200;

    private RecyclerView overviewRecycler;
    private TextView emptyText;

    private boolean isCameraAppInstalled = false;
    private GalleryOverviewAdapter overviewAdapter;
    private RecyclerView.LayoutManager layoutManager;

    private final Handler handler = new Handler();

    @Override
    public View onCreateView (
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        //Intent dummy = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        //isCameraAppInstalled = dummy.resolveActivity(requireActivity().getPackageManager()) != null;

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_gallery_overview, container, false);
    }

    public void onViewCreated (@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FloatingActionButton importButton = view.findViewById(R.id.button_import);
        //FloatingActionButton cameraButton = view.findViewById(R.id.button_camera);
        emptyText = view.findViewById(R.id.text_empty_gallery);
        overviewRecycler = view.findViewById(R.id.recycler_gallery_overview);

        overviewAdapter = new GalleryOverviewAdapter(this);
        layoutManager = new ConstantWidthGridLayoutManager(requireContext(), 200);

        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick (View view) {
                Intent gallery = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(gallery, IMPORT_IMAGE);
            }
        });

        initRecycler();
        /*
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                File photo = getPhotoUri(getCurrentTimestamp()+".jpg", requireContext());

                Uri fileProvider = FileProvider.getUriForFile(requireContext(), "neue.project.invisiblegallery", photo);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileProvider);

                if (isCameraAppInstalled) {
                    startActivityForResult(takePictureIntent, TAKE_PHOTO);
                } else {
                    Snackbar.make(view, R.string.no_camera_error, Snackbar.LENGTH_SHORT).show();
                }
            }
        });*/
    }

    @Override
    public void onResume () {
        super.onResume();

        new Thread(new Runnable(){
            @Override
            public void run () {
                final List <Image> images = Database
                        .open(requireContext().getApplicationContext())
                        .imageDao()
                        .getAll();
                handler.post(new Runnable() {
                    @Override
                    public void run () {
                        overviewAdapter.refresh(images);
                    }
                });
            }
        }).start();
    }

    private void initRecycler () {
        overviewRecycler.setAdapter(overviewAdapter);
        overviewRecycler.setLayoutManager(layoutManager);
    }

    @Override
    public void onActivityResult (int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_CANCELED) return;

        if (data != null) {
            final Uri photoUri = data.getData();

            try {
                final ContentResolver resolver = requireActivity().getContentResolver();
                ParcelFileDescriptor parcelFileDescriptor = resolver.openFileDescriptor(photoUri, "r");
                assert parcelFileDescriptor != null;
                final FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();

                new Thread(new Runnable(){
                    @Override
                    public void run () {
                        try {
                            Bitmap thumbnail = Util.getThumbnail(photoUri, resolver);
                            confirmOverride(requireContext(), Util.getFileName(photoUri, resolver), thumbnail, fileDescriptor);
                        } catch (final IOException e) {
                            e.printStackTrace();
                            handler.post(new Runnable() {
                                @Override
                                public void run () {
                                    showError(e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void showError( String cause){
        Snackbar
                .make(overviewRecycler, "Error while importing: "+cause, Snackbar.LENGTH_LONG)
                .setBackgroundTint(getResources().getColor(R.color.colorBackgroundLight))
                .show();
    }

    private void confirmOverride (final Context context, final String name, final Bitmap thumbnail, final FileDescriptor descriptor) {
        final ImageDao db = Database.open(context.getApplicationContext()).imageDao();
        final List<Image> images = db.findByName(name);
        if (!images.isEmpty()){
            final DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which){
                        case DialogInterface.BUTTON_POSITIVE:
                            overviewAdapter.remove(images.get(0));

                            new Thread(new Runnable(){
                                @Override
                                public void run () {
                                    db.delete(name);
                                    addImage(context.getApplicationContext(), descriptor, name, thumbnail);
                                }
                            }).start();
                            break;

                        case DialogInterface.BUTTON_NEGATIVE:
                            break;
                    }
                }
            };

            handler.post(new Runnable() {
                @Override
                public void run () {
                    new AlertDialog
                            .Builder(context)
                            .setMessage(R.string.duplicate_error)
                            .setPositiveButton("Override", dialogClickListener)
                            .setNegativeButton("Cancel", dialogClickListener)
                            .show();
                }
            });
        } else {
            addImage(context, descriptor, name, thumbnail);
        }
    }

    private void addImage(final Context context, final FileDescriptor descriptor, final String name, Bitmap thumbnail){
        try {
            final Image image = Util.importFile(context, descriptor, name, thumbnail);

            handler.post(new Runnable() {
                @Override
                public void run () {
                    overviewAdapter.add(image);
                    overviewRecycler.smoothScrollToPosition(overviewAdapter.getItemCount() - 1);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            showError(e.getMessage());
        }
    }

    @Override
    public void onEmpty () {
        emptyText.setVisibility(View.VISIBLE);
    }

    @Override
    public void onNotEmpty () {
        emptyText.setVisibility(View.INVISIBLE);
    }
}
