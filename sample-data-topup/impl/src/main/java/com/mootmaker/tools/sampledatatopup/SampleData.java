package com.mootmaker.tools.sampledatatopup;

import module java.base;

/**
 * Curated, realistic meeting subjects used when topping up empty business days. Duplicated from
 * sample-data-generator's own copy (see {@link GraphQlClient}'s doc comment for why), trimmed to
 * just the subjects - this tool reuses whatever rooms already exist rather than creating new
 * ones, so it has no need for sample-data-generator's room name list.
 */
final class SampleData {

    /** Realistic meeting subjects covering common recurring and one-off meeting types. */
    static final List<String> MEETING_SUBJECTS = List.of(
            "Weekly Team Sync", "Sprint Planning", "Sprint Retrospective", "Q3 Budget Review",
            "Client Onboarding Call", "1:1 Check-in", "Design Review", "All-Hands Meeting",
            "Vendor Negotiation", "Performance Review", "Product Roadmap Review",
            "Marketing Strategy Session", "Interview: Senior Engineer", "Town Hall",
            "Onboarding Orientation", "Architecture Review", "Customer Feedback Session",
            "Release Planning", "Security Review", "Offsite Planning Committee");

    private SampleData() {
    }
}
