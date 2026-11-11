package lvregister.pipes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The encoding of the Pipes bootstrap parameters in environment variables:
 * {@code base64(zlib(json))}, as {@code encode_param} and {@code decode_param} in dagster-pipes.
 */
public final class PipesParams {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private PipesParams() {}

    public static JsonNode decode(String value) {
        byte[] compressed = Base64.getDecoder().decode(value.strip());
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    throw new IllegalArgumentException("truncated Pipes parameter");
                }
                out.write(buffer, 0, n);
            }
            return JSON.readTree(out.toString(StandardCharsets.UTF_8));
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Pipes parameter is not zlib data", e);
        } finally {
            inflater.end();
        }
    }

    public static String encode(Object value) {
        byte[] json = JSON.writeValueAsBytes(value);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(json);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } finally {
            deflater.end();
        }
    }
}
