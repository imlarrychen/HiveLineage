package com.panfan.hivelineage.v2.compare;

public final class DefaultLineageDifferenceRenderer implements LineageDifferenceRenderer {
    @Override
    public String render(LineageDifferenceReport report) {
        StringBuilder text = new StringBuilder("# V1 / V2 lineage comparison\n\n");
        text.append("Corpus: ").append(report.getCorpusId()).append("; total cases: ").append(report.getTotalCases())
                .append("; unsupported: ").append(report.getUnsupportedCases()).append("; pending review: ").append(report.getPendingCases()).append("\n\n");
        text.append("| Case | Statement | Kind | Disposition | Detail |\n| --- | --- | --- | --- | --- |\n");
        for (Difference difference : report.getDifferences()) {
            text.append("| ").append(escape(difference.getCaseId())).append(" | ")
                    .append(difference.getStatement().map(statement -> Integer.toString(statement.getStatementIndex())).orElse("script"))
                    .append(" | ").append(difference.getKind()).append(" | ").append(difference.getDisposition()).append(" | ")
                    .append(escape(difference.getDescription())).append(" |\n");
        }
        return text.toString();
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }
}
