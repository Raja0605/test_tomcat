package com.shartechnologies.documents;

import java.time.Instant;

public record DocumentMetadata(String filename, long size, Instant uploadedAt, String url) {
}
