package com.example.coderiskanalyzer.mutation;

public record MutationEvidence(String mutatedClass, String mutatedMethod, int line, String mutator,
                               MutationStatus status, String description) {
    /** Identity uses every location/description field available in the current PIT model. */
    public String fingerprint() {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var output = new java.io.DataOutputStream(bytes);
            for (String field : java.util.List.of(mutatedClass, mutatedMethod, Integer.toString(line), mutator, description)) {
                byte[] encoded = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint mutation", exception);
        }
    }
}
