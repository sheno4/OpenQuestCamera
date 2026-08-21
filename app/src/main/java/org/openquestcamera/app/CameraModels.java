package org.openquestcamera.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

final class CameraModels {
    static final int[] FPS_CANDIDATES = new int[]{25, 30, 50, 60};

    static final class RecorderConfig {
        final int width, height, fps, bitrate, convergencePx, verticalPx;
        final String codec;
        final boolean mic, preview, swap, saveOriginals;

        RecorderConfig(int width, int height, int fps, int bitrate, String codec,
                       boolean mic, boolean preview, boolean swap, boolean saveOriginals,
                       int convergencePx, int verticalPx) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            this.codec = codec;
            this.mic = mic;
            this.preview = preview;
            this.swap = swap;
            this.saveOriginals = saveOriginals;
            this.convergencePx = convergencePx;
            this.verticalPx = verticalPx;
        }
    }

    static final class Mode {
        final int width, height;
        final Range<Integer>[] leftRanges, rightRanges;
        final boolean[] reachable;

        @SuppressWarnings("unchecked")
        Mode(int width, int height, Range<Integer>[] leftRanges, Range<Integer>[] rightRanges, boolean[] reachable) {
            this.width = width;
            this.height = height;
            this.leftRanges = leftRanges;
            this.rightRanges = rightRanges;
            this.reachable = reachable;
        }

        private int indexOf(int fps) {
            for (int i = 0; i < FPS_CANDIDATES.length; i++) if (FPS_CANDIDATES[i] == fps) return i;
            return -1;
        }

        Range<Integer> leftRange(int fps) {
            int i = indexOf(fps);
            return i >= 0 ? leftRanges[i] : null;
        }

        Range<Integer> rightRange(int fps) {
            int i = indexOf(fps);
            return i >= 0 ? rightRanges[i] : null;
        }

        boolean cameraSupports(int fps) {
            int i = indexOf(fps);
            return i >= 0 && reachable[i] && leftRanges[i] != null && rightRanges[i] != null;
        }

        boolean supports(int fps, String codec) {
            return cameraSupports(fps) && VideoRecorder.supportsVideo(codec, width * 2, height, fps);
        }

        String label(Context context) {
            return context.getString(R.string.resolution_per_eye, width, height);
        }
    }

    static final class Pair {
        static final CameraCharacteristics.Key<Integer> KEY_SOURCE =
                new CameraCharacteristics.Key<Integer>("com.meta.extra_metadata.camera_source", Integer.class);
        static final CameraCharacteristics.Key<Integer> KEY_POSITION =
                new CameraCharacteristics.Key<Integer>("com.meta.extra_metadata.position", Integer.class);

        final String leftId, rightId;
        final ArrayList<Mode> modes;

        Pair(String leftId, String rightId, ArrayList<Mode> modes) {
            this.leftId = leftId;
            this.rightId = rightId;
            this.modes = modes;
        }

        static Pair discover(Context context) throws Exception {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraInfo left = null, right = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer source;
                Integer position;
                try {
                    source = c.get(KEY_SOURCE);
                    position = c.get(KEY_POSITION);
                } catch (Throwable ignored) {
                    continue;
                }
                if (source == null || source.intValue() != 0 || position == null) continue;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Set<String> sizes = surfaceSizes(map);
                if (sizes.isEmpty()) continue;
                CameraInfo info = new CameraInfo(id, c, map, sizes);
                if (position.intValue() == 0) left = info;
                else if (position.intValue() == 1) right = info;
            }
            if (left == null || right == null) return null;

            ArrayList<Size> common = new ArrayList<Size>();
            for (String key : left.sizes) {
                if (!right.sizes.contains(key)) continue;
                String[] p = key.split("x");
                if (p.length == 2) common.add(new Size(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
            }
            Collections.sort(common, new Comparator<Size>() {
                @Override public int compare(Size a, Size b) {
                    long aa = (long) a.getWidth() * a.getHeight();
                    long bb = (long) b.getWidth() * b.getHeight();
                    if (aa != bb) return aa > bb ? -1 : 1;
                    return Integer.compare(b.getWidth(), a.getWidth());
                }
            });

            ArrayList<Mode> modes = new ArrayList<Mode>();
            for (Size size : common) {
                @SuppressWarnings("unchecked") Range<Integer>[] lr = new Range[FPS_CANDIDATES.length];
                @SuppressWarnings("unchecked") Range<Integer>[] rr = new Range[FPS_CANDIDATES.length];
                boolean[] reachable = new boolean[FPS_CANDIDATES.length];
                boolean usable = false;
                for (int i = 0; i < FPS_CANDIDATES.length; i++) {
                    int fps = FPS_CANDIDATES[i];
                    lr[i] = chooseRange(left.characteristics, fps);
                    rr[i] = chooseRange(right.characteristics, fps);
                    reachable[i] = lr[i] != null && rr[i] != null
                            && streamCanReach(left.map, size, fps)
                            && streamCanReach(right.map, size, fps);
                    if (reachable[i] && (VideoRecorder.supportsVideo("video/avc", size.getWidth() * 2, size.getHeight(), fps)
                            || VideoRecorder.supportsVideo("video/hevc", size.getWidth() * 2, size.getHeight(), fps))) {
                        usable = true;
                    }
                }
                if (usable) modes.add(new Mode(size.getWidth(), size.getHeight(), lr, rr, reachable));
            }
            return new Pair(left.id, right.id, modes);
        }

        private static Set<String> surfaceSizes(StreamConfigurationMap map) {
            Set<String> out = new HashSet<String>();
            Size[] sizes = null;
            try { sizes = map.getOutputSizes(SurfaceTexture.class); } catch (Throwable ignored) {}
            if (sizes == null || sizes.length == 0) {
                try { sizes = map.getOutputSizes(ImageFormat.YUV_420_888); } catch (Throwable ignored) {}
            }
            if (sizes != null) {
                for (Size s : sizes) {
                    if (s.getWidth() > 0 && s.getHeight() > 0 && (s.getWidth() & 1) == 0 && (s.getHeight() & 1) == 0) {
                        out.add(s.getWidth() + "x" + s.getHeight());
                    }
                }
            }
            return out;
        }

        private static boolean streamCanReach(StreamConfigurationMap map, Size size, int fps) {
            long duration = 0;
            try { duration = map.getOutputMinFrameDuration(SurfaceTexture.class, size); } catch (Throwable ignored) {}
            if (duration <= 0) {
                try { duration = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size); } catch (Throwable ignored) {}
            }
            return duration <= 0 || duration <= (long) (1_000_000_000.0 / (fps * 0.95));
        }

        @SuppressWarnings("unchecked")
        private static Range<Integer> chooseRange(CameraCharacteristics c, int target) {
            Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null) return null;
            Range<Integer> best = null;
            for (Range<Integer> range : ranges) {
                if (!range.contains(target)) continue;
                if (range.getLower().intValue() == target && range.getUpper().intValue() == target) return range;
                if (best == null || span(range) < span(best)
                        || (span(range) == span(best) && range.getLower().intValue() > best.getLower().intValue())) {
                    best = range;
                }
            }
            return best;
        }

        private static int span(Range<Integer> range) {
            return range.getUpper().intValue() - range.getLower().intValue();
        }
    }

    private static final class CameraInfo {
        final String id;
        final CameraCharacteristics characteristics;
        final StreamConfigurationMap map;
        final Set<String> sizes;

        CameraInfo(String id, CameraCharacteristics characteristics, StreamConfigurationMap map, Set<String> sizes) {
            this.id = id;
            this.characteristics = characteristics;
            this.map = map;
            this.sizes = sizes;
        }
    }

    private CameraModels() {}
}
