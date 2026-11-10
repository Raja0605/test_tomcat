package com.shartechnologies.documents;

import java.time.Instant;
import java.util.List;

public record PermissionCheckResponse(Instant checkedAt, List<PermissionTestResult> tests, String note) {
}
