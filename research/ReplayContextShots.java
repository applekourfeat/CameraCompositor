import dev.cameracompositor.*;
import java.io.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Uses the Android candidate policy and score aggregation with desktop models. */
class ReplayContextShots {
    private static SceneLayout layout(String path) throws IOException {
        var image = ImageIO.read(new File(path));
        var reduced = new BufferedImage(SceneLayout.WIDTH, SceneLayout.HEIGHT, BufferedImage.TYPE_INT_RGB);
        var canvas = reduced.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.drawImage(image, 0, 0, reduced.getWidth(), reduced.getHeight(), null);
        } finally { canvas.dispose(); }
        int[] rgb = reduced.getRGB(0, 0, reduced.getWidth(), reduced.getHeight(), null, 0, reduced.getWidth());
        return new SceneLayout(rgb, null);
    }

    public static void main(String[] args) throws Exception {
        boolean adaptive = List.of(args).contains("--adaptive");
        boolean useRanker = !List.of(args).contains("--geometry-only");
        boolean window = List.of(args).contains("--window");
        try (var in = new DataInputStream(new BufferedInputStream(System.in))) {
            int length;
            while ((length = in.readInt()) > 0) {
                String path = new String(in.readNBytes(length), StandardCharsets.UTF_8);
                int width = in.readInt(), height = in.readInt();
                float zoom = in.readFloat();
                in.readFloat();
                var detections = new ArrayList<DetectedSubject>();
                for (int stride : SubjectDetection.INSTANCE.getStrides()) {
                    int side = SubjectDetection.SIZE / stride;
                    var score = ByteBuffer.wrap(in.readNBytes(side * side * 80 * Float.BYTES)).asFloatBuffer();
                    var bounds = ByteBuffer.wrap(in.readNBytes(side * side * 32 * Float.BYTES)).asFloatBuffer();
                    detections.addAll(SubjectDetection.INSTANCE.decode(score, bounds, stride, width, height));
                }
                int count = SceneRegions.GRID * SceneRegions.GRID * SceneRegions.INSTANCE.getPROMPT_GROUP().size();
                var patches = new float[count];
                for (int i = 0; i < count; i++) patches[i] = in.readFloat();
                var layout = layout(path);
                var map = new EnvironmentMap(1);
                int history = window ? in.readInt() : 0;
                if (history < 0 || history > 2) throw new IOException("Invalid observation count");
                var previousLayouts = new ArrayList<SceneLayout>();
                var previousViews = new ArrayList<EnvironmentView>();
                ViewRegistration link = null;
                for (int i = 0; i < history; i++) {
                    var earlier = new EnvironmentView(i, 1 + 120L * i, zoom, 3.2f, link, i == 0 ? null : i-1, null);
                    map.add(earlier);
                    previousViews.add(earlier);
                    previousLayouts.add(layout(new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8)));
                    link = null;
                    if (in.readBoolean()) {
                        int inliers = in.readInt(), tracks = in.readInt();
                        float motion = in.readFloat(), error = in.readFloat(), coverage = in.readFloat();
                        var matrix = new ArrayList<Float>();
                        for (int j = 0; j < 9; j++) matrix.add(in.readFloat());
                        link = new ViewRegistration(inliers, tracks, motion, error, coverage, new ImageProjection(matrix), null);
                    }
                }
                var view = new EnvironmentView(history, 1 + 120L * history, zoom, 3.2f, link, history == 0 ? null : history-1, null);
                map.add(view);
                var subjects = adaptive ? ContextShots.INSTANCE.observedSubjects(layout, patches, detections) :
                    ContextShots.INSTANCE.subjects(layout, patches, SubjectDetection.INSTANCE.mainSubject(detections, null));
                var evidence = new ShotEvidence(view, layout, subjects, detections);
                SubjectBox landmark = null;
                if (subjects.stream().anyMatch(s -> s.getDetected() && s.getKind() == SceneKind.PERSON)) {
                    for (int i = history-1; i >= 0 && landmark == null; i--) {
                        landmark = RegisteredLandmark.INSTANCE.recover(previousLayouts.get(i), layout, map, previousViews.get(i), view);
                    }
                }
                var shots = adaptive ? AdaptiveFraming.INSTANCE.candidates(map, evidence, 3.2f, null, landmark) :
                    ContextShots.INSTANCE.candidates(map, List.of(evidence), CompositionStyle.THIRDS, 3.2f, null, null);
                System.out.println(shots.size());
                for (var shot : shots) {
                    var box = shot.getComparisonBox();
                    System.out.println(box.getLeft() + " " + box.getTop() + " " + box.getRight() + " " + box.getBottom() +
                        " " + (shot.getCue() != null ? shot.getCue().name() :
                        shot.getSubject() == null ? "wide" : shot.getSubject().getKind().name() +
                        (shot.getSubject().getPaired() ? "_PAIR" : "")));
                }
                System.out.flush();
                var scores = new float[shots.size()];
                for (int i = 0; i < scores.length; i++) scores[i] = in.readFloat();
                if (adaptive) {
                    var chosen = AdaptiveFraming.INSTANCE.choose(shots, useRanker ? scores : null);
                    System.out.println(chosen == null ? "0 0 false" :
                        shots.indexOf(chosen.getShot()) + " " + chosen.getGain() + " " + chosen.getCanAutoCapture());
                } else {
                    var chosen = ContextShots.INSTANCE.choose(shots, scores);
                    System.out.println(chosen == null ? "0 0" : shots.indexOf(chosen.getShot()) + " " + chosen.getGain());
                }
                System.out.flush();
            }
        }
    }
}
