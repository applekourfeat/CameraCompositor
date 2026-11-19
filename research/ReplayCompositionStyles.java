import dev.cameracompositor.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import javax.imageio.ImageIO;

/** Desktop driver for the app's pure planning code. Input comes from evaluate_composition_styles.py. */
class ReplayCompositionStyles {
    private static final SubjectBox FULL = new SubjectBox(0f, 0f, 1f, 1f);

    private static String coordinates(SubjectBox box) {
        return box.getLeft() + "," + box.getTop() + "," + box.getRight() + "," + box.getBottom();
    }

    private static SceneLayout layout(String path) throws Exception {
        var original = ImageIO.read(new File(path));
        var resized = new BufferedImage(SceneLayout.WIDTH, SceneLayout.HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, 0, 0, resized.getWidth(), resized.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        var rgb = resized.getRGB(0, 0, resized.getWidth(), resized.getHeight(), null, 0, resized.getWidth());
        return new SceneLayout(rgb, null);
    }

    public static void main(String[] args) throws Exception {
        try (var input = new DataInputStream(new BufferedInputStream(System.in))) {
            int pathLength;
            SubjectHint expected = null;
            while ((pathLength = input.readInt()) != 0) {
                if (pathLength < 0 || pathLength > 65536) throw new IllegalArgumentException("Invalid image path length");
                String path = new String(input.readNBytes(pathLength), StandardCharsets.UTF_8);
                int width = input.readInt();
                int height = input.readInt();
                float x = input.readFloat();
                float y = input.readFloat();
                var point = x >= 0f ? new ScenePoint(x, y) : null;
                var detections = new ArrayList<DetectedSubject>();
                for (int stride : SubjectDetection.INSTANCE.getStrides()) {
                    int side = SubjectDetection.SIZE / stride;
                    var scores = ByteBuffer.wrap(input.readNBytes(side * side * 80 * Float.BYTES)).asFloatBuffer();
                    var boxes = ByteBuffer.wrap(input.readNBytes(side * side * 32 * Float.BYTES)).asFloatBuffer();
                    detections.addAll(SubjectDetection.INSTANCE.decode(scores, boxes, stride, width, height));
                }
                var subject = SubjectDetection.INSTANCE.mainSubject(detections, point);
                var map = new EnvironmentMap(1);
                map.add(new EnvironmentView(0, 1, 1f, 2.2f, null, null, null));
                var layouts = Map.of(0, layout(path));
                var selected = point == null ? null : new SelectedSubject(point, 0);
                if (args.length == 1 && args[0].equals("--discovery")) {
                    int count = SceneRegions.GRID * SceneRegions.GRID * SceneRegions.INSTANCE.getPROMPT_GROUP().size();
                    var scores = new float[count];
                    for (int i = 0; i < count; i++) scores[i] = input.readFloat();
                    boolean allowExploration = input.readBoolean();
                    var previous = StyledComposition.INSTANCE.choose(CompositionStyle.THIRDS, map, layouts, 2.2f, subject, null, null);
                    var proposed = SceneDiscovery.INSTANCE.choose(CompositionStyle.THIRDS, map, layouts, 2.2f, subject, null,
                        scores, allowExploration, allowExploration ? null : expected);
                    var top = proposed.getRegion();
                    var decision = proposed.getPlan().getDecision();
                    if (allowExploration) {
                        expected = decision instanceof EnvironmentDecision.Explore next ? next.getExpected() : null;
                    }
                    var frame = decision instanceof EnvironmentDecision.Explore explore ? NextView.INSTANCE.frame(explore.getAim())
                        : decision instanceof EnvironmentDecision.Guide guide ? guide.getCrop() : FULL;
                    System.out.println(String.join("\t", previous.getDecision().getClass().getSimpleName(),
                        decision.getClass().getSimpleName(), coordinates(frame),
                        top == null ? "-" : top.getKind().toString(),
                        top == null ? "-" : coordinates(top.getBounds()),
                        top == null ? "0" : Float.toString(top.getStrength()),
                        subject == null ? "0" : Float.toString(subject.getConfidence()),
                        Boolean.toString(proposed.getPlan().getCanAutoCapture())));
                    continue;
                }
                var result = new ArrayList<String>();
                result.add(subject == null ? "-" : coordinates(subject.getBox()));
                result.add(subject == null ? "0" : Float.toString(subject.getConfidence()));
                var prior = ObservedComposition.INSTANCE.choose(map, layouts, 2.2f, subject, null);
                result.add(prior instanceof EnvironmentDecision.Guide guide ? coordinates(guide.getCrop()) : coordinates(FULL));
                for (var style : CompositionStyle.values()) {
                    var assessment = StyledComposition.INSTANCE.choose(style, map, layouts, 2.2f, subject, selected, null);
                    var decision = assessment.getDecision();
                    var box = decision instanceof EnvironmentDecision.Guide guide ? guide.getCrop() : FULL;
                    result.add(decision.getClass().getSimpleName());
                    result.add(coordinates(box));
                    result.add(Boolean.toString(assessment.getCanAutoCapture()));
                }
                System.out.println(String.join("\t", result));
            }
        }
    }
}
