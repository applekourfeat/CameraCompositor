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
                var view = new EnvironmentView(0, 1, zoom, 3.2f, null, null, null);
                var map = new EnvironmentMap(1);
                map.add(view);
                var subjects = ContextShots.INSTANCE.subjects(layout, patches, SubjectDetection.INSTANCE.mainSubject(detections, null));
                var shots = ContextShots.INSTANCE.candidates(map, List.of(new ShotEvidence(view, layout, subjects)),
                    CompositionStyle.THIRDS, 3.2f, null);
                System.out.println(shots.size());
                for (var shot : shots) {
                    var box = shot.getComparisonBox();
                    System.out.println(box.getLeft() + " " + box.getTop() + " " + box.getRight() + " " + box.getBottom() +
                        " " + (shot.getSubject() == null ? "wide" : shot.getSubject().getKind().name()));
                }
                System.out.flush();
                var scores = new float[shots.size()];
                for (int i = 0; i < scores.length; i++) scores[i] = in.readFloat();
                var chosen = ContextShots.INSTANCE.choose(shots, scores);
                System.out.println(chosen == null ? "0 0" : shots.indexOf(chosen.getShot()) + " " + chosen.getGain());
                System.out.flush();
            }
        }
    }
}
