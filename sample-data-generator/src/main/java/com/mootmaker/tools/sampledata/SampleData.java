package com.mootmaker.tools.sampledata;

import module java.base;

/** Curated, meaningful names and subjects used to generate realistic-looking sample data. */
final class SampleData {

    /** Meeting room names - a mix of geography/nature themes common for real meeting rooms. */
    static final List<String> ROOM_NAMES = List.of(
            "Everest", "Kilimanjaro", "Fjord", "Horizon", "The Hub", "Innovation Lab",
            "Boardroom", "Atrium", "Sunroom", "Skyline Suite", "Aurora", "Basecamp",
            "Meridian", "Compass");

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
