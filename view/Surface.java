package android.view;

import android.content.res.CompatibilityInfo.Translator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import dalvik.system.CloseGuard;

/**
 * Handle onto a raw buffer that is being managed by the （screen compositor）——>屏幕图像合成器.
	surface就是一个指向图像原始缓冲区的句柄。handle是句柄的意思。可能跟智能指针相似。
 */
public class Surface implements Parcelable {
    private static final String TAG = "Surface";

    private static native int nativeCreateFromSurfaceTexture(SurfaceTexture surfaceTexture)
            throws OutOfResourcesException;
    private static native int nativeCreateFromSurfaceControl(int surfaceControlNativeObject);

    private static native void nativeLockCanvas(int nativeObject, Canvas canvas, Rect dirty)
            throws OutOfResourcesException;
    private static native void nativeUnlockCanvasAndPost(int nativeObject, Canvas canvas);

    private static native void nativeRelease(int nativeObject);
    private static native boolean nativeIsValid(int nativeObject);
    private static native boolean nativeIsConsumerRunningBehind(int nativeObject);
    private static native int nativeReadFromParcel(int nativeObject, Parcel source);
    private static native void nativeWriteToParcel(int nativeObject, Parcel dest);

    public static final Parcelable.Creator<Surface> CREATOR =
            new Parcelable.Creator<Surface>() {
        @Override
        public Surface createFromParcel(Parcel source) {
            try {
                Surface s = new Surface();
                s.readFromParcel(source);
                return s;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating surface from parcel", e);
                return null;
            }
        }

        @Override
        public Surface[] newArray(int size) {
            return new Surface[size];
        }
    };

    private final CloseGuard mCloseGuard = CloseGuard.get();

    // Guarded state.
    final Object mLock = new Object(); // protects the native state
    private String mName;
    int mNativeSurface; // package scope only for SurfaceControl access
    private int mGenerationId; // incremented each time mNativeSurface changes
    private final Canvas mCanvas = new CompatibleCanvas();

    // A matrix to scale the matrix set by application. This is set to null for
    // non compatibility mode.
    private Matrix mCompatibleMatrix;

    /**
     * Rotation constant: 0 degree rotation (natural orientation)
     */
    public static final int ROTATION_0 = 0;

    /**
     * Rotation constant: 90 degree rotation.
     */
    public static final int ROTATION_90 = 1;

    /**
     * Rotation constant: 180 degree rotation.
     */
    public static final int ROTATION_180 = 2;

    /**
     * Rotation constant: 270 degree rotation.
     */
    public static final int ROTATION_270 = 3;

    /**
     * Create an empty surface, which will later be filled in by readFromParcel().
     * @hide
     */
    public Surface() {
    }

    /**
     * Create Surface from a {@link SurfaceTexture}.
     *
     * Images drawn to the Surface will be made available to the {@link
     * SurfaceTexture}, which can attach them to an OpenGL ES texture via {@link
     * SurfaceTexture#updateTexImage}.
     *
     * @param surfaceTexture The {@link SurfaceTexture} that is updated by this
     * Surface.
     */
    public Surface(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) {
            throw new IllegalArgumentException("surfaceTexture must not be null");
        }

        synchronized (mLock) {
            mName = surfaceTexture.toString();
            try {
                setNativeObjectLocked(nativeCreateFromSurfaceTexture(surfaceTexture));
            } catch (OutOfResourcesException ex) {
                // We can't throw OutOfResourcesException because it would be an API change.
                throw new RuntimeException(ex);
            }
        }
    }

    /* called from android_view_Surface_createFromIGraphicBufferProducer() */
    private Surface(int nativeObject) {
        synchronized (mLock) {
            setNativeObjectLocked(nativeObject);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Release the local reference to the server-side surface.
     * Always call release() when you're done with a Surface.
     * This will make the surface invalid.
     */
    public void release() {
        synchronized (mLock) {
            if (mNativeSurface != 0) {
                nativeRelease(mNativeSurface);
                setNativeObjectLocked(0);
            }
        }
    }

    /**
     * Free all server-side state associated with this surface and
     * release this object's reference.  This method can only be
     * called from the process that created the service.
     * @hide
     */
    public void destroy() {
        release();
    }

    /**
     * Returns true if this object holds a valid surface.
     *
     * @return True if it holds a physical surface, so lockCanvas() will succeed.
     * Otherwise returns false.
     */
    public boolean isValid() {
        synchronized (mLock) {
            if (mNativeSurface == 0) return false;
            return nativeIsValid(mNativeSurface);
        }
    }

    /**
     * Gets the generation number of this surface, incremented each time
     * the native surface contained within this object changes.
     *
     * @return The current generation number.
     * @hide
     */
    public int getGenerationId() {
        synchronized (mLock) {
            return mGenerationId;
        }
    }

    /**
     * Returns true if the consumer of this Surface is running behind the producer.
     *
     * @return True if the consumer is more than one buffer ahead of the producer.
     * @hide
     */
    public boolean isConsumerRunningBehind() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            return nativeIsConsumerRunningBehind(mNativeSurface);
        }
    }

    /**
     * Gets a {@link Canvas} for drawing into this surface.
     *
     * After drawing into the provided {@link Canvas}, the caller must
     * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
     *
     * @param inOutDirty A rectangle that represents the dirty region that the caller wants
     * to redraw.  This function may choose to expand the dirty rectangle if for example
     * the surface has been resized or if the previous contents of the surface were
     * not available.  The caller must redraw the entire dirty region as represented
     * by the contents of the inOutDirty rectangle upon return from this function.
     * The caller may also pass <code>null</code> instead, in the case where the
     * entire surface should be redrawn.
     * @return A canvas for drawing into the surface.
     */
    public Canvas lockCanvas(Rect inOutDirty)
            throws OutOfResourcesException, IllegalArgumentException {
        synchronized (mLock) {
            checkNotReleasedLocked();
            nativeLockCanvas(mNativeSurface, mCanvas, inOutDirty);
            return mCanvas;
        }
    }

    /**
     * Posts the new contents of the {@link Canvas} to the surface and
     * releases the {@link Canvas}.
     *
     * @param canvas The canvas previously obtained from {@link #lockCanvas}.
     */
    public void unlockCanvasAndPost(Canvas canvas) {
        if (canvas != mCanvas) {
            throw new IllegalArgumentException("canvas object must be the same instance that "
                    + "was previously returned by lockCanvas");
        }

        synchronized (mLock) {
            checkNotReleasedLocked();
            nativeUnlockCanvasAndPost(mNativeSurface, canvas);
        }
    }

    /** 
     * @deprecated This API has been removed and is not supported.  Do not use.
     */
    @Deprecated
    public void unlockCanvas(Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the translator used to scale canvas's width/height in compatibility
     * mode.
     */
    void setCompatibilityTranslator(Translator translator) {
        if (translator != null) {
            float appScale = translator.applicationScale;
            mCompatibleMatrix = new Matrix();
            mCompatibleMatrix.setScale(appScale, appScale);
        }
    }

    /**
     * Copy another surface to this one.  This surface now holds a reference
     * to the same data as the original surface, and is -not- the owner.
     * This is for use by the window manager when returning a window surface
     * back from a client, converting it from the representation being managed
     * by the window manager to the representation the client uses to draw
     * in to it.
     * @hide
     */
    public void copyFrom(SurfaceControl other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }

        int surfaceControlPtr = other.mNativeObject;
        if (surfaceControlPtr == 0) {
            throw new NullPointerException(
                    "SurfaceControl native object is null. Are you using a released SurfaceControl?");
        }
        int newNativeObject = nativeCreateFromSurfaceControl(surfaceControlPtr);

        synchronized (mLock) {
            if (mNativeSurface != 0) {
                nativeRelease(mNativeSurface);
            }
            setNativeObjectLocked(newNativeObject);
        }
    }

    /**
     * This is intended to be used by {@link SurfaceView#updateWindow} only.
     * @param other access is not thread safe
     * @hide
     * @deprecated
     */
    @Deprecated
    public void transferFrom(Surface other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        if (other != this) {
            final int newPtr;
            synchronized (other.mLock) {
                newPtr = other.mNativeSurface;
                other.setNativeObjectLocked(0);
            }

            synchronized (mLock) {
                if (mNativeSurface != 0) {
                    nativeRelease(mNativeSurface);
                }
                setNativeObjectLocked(newPtr);
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        synchronized (mLock) {
            mName = source.readString();
            setNativeObjectLocked(nativeReadFromParcel(mNativeSurface, source));
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        synchronized (mLock) {
            dest.writeString(mName);
            nativeWriteToParcel(mNativeSurface, dest);
        }
        if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            release();
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "Surface(name=" + mName + ")";
        }
    }

    private void setNativeObjectLocked(int ptr) {
        if (mNativeSurface != ptr) {
            if (mNativeSurface == 0 && ptr != 0) {
                mCloseGuard.open("release");
            } else if (mNativeSurface != 0 && ptr == 0) {
                mCloseGuard.close();
            }
            mNativeSurface = ptr;
            mGenerationId += 1;
        }
    }

    private void checkNotReleasedLocked() {
        if (mNativeSurface == 0) {
            throw new IllegalStateException("Surface has already been released.");
        }
    }

    /**
     * Exception thrown when a surface couldn't be created or resized.
     */
    public static class OutOfResourcesException extends Exception {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /**
     * Returns a human readable representation of a rotation.
     *
     * @param rotation The rotation.
     * @return The rotation symbolic name.
     *
     * @hide
     */
    public static String rotationToString(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0: {
                return "ROTATION_0";
            }
            case Surface.ROTATION_90: {
                return "ROATATION_90";
            }
            case Surface.ROTATION_180: {
                return "ROATATION_180";
            }
            case Surface.ROTATION_270: {
                return "ROATATION_270";
            }
            default: {
                throw new IllegalArgumentException("Invalid rotation: " + rotation);
            }
        }
    }

    /**
     * A Canvas class that can handle the compatibility mode.
     * This does two things differently.
     * <ul>
     * <li>Returns the width and height of the target metrics, rather than
     * native. For example, the canvas returns 320x480 even if an app is running
     * in WVGA high density.
     * <li>Scales the matrix in setMatrix by the application scale, except if
     * the matrix looks like obtained from getMatrix. This is a hack to handle
     * the case that an application uses getMatrix to keep the original matrix,
     * set matrix of its own, then set the original matrix back. There is no
     * perfect solution that works for all cases, and there are a lot of cases
     * that this model does not work, but we hope this works for many apps.
     * </ul>
     */
    private final class CompatibleCanvas extends Canvas {
        // A temp matrix to remember what an application obtained via {@link getMatrix}
        private Matrix mOrigMatrix = null;

        @Override
        public void setMatrix(Matrix matrix) {
            if (mCompatibleMatrix == null || mOrigMatrix == null || mOrigMatrix.equals(matrix)) {
                // don't scale the matrix if it's not compatibility mode, or
                // the matrix was obtained from getMatrix.
                super.setMatrix(matrix);
            } else {
                Matrix m = new Matrix(mCompatibleMatrix);
                m.preConcat(matrix);
                super.setMatrix(m);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void getMatrix(Matrix m) {
            super.getMatrix(m);
            if (mOrigMatrix == null) {
                mOrigMatrix = new Matrix(); 
            }
            mOrigMatrix.set(m);
        }
    }
}