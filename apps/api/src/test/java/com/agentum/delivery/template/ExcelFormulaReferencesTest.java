package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExcelFormulaReferencesTest {
    private final ExcelFormulaReferences references = new ExcelFormulaReferences(
        List.of(new ExcelFormulaReferences.Insert("Detail", false, 2, 3)),
        List.of(new ExcelFormulaReferences.Detail("Detail", 1, 1, false, 4)));
    @Test void preservesStringsStructuredReferencesAndRelativeAbsoluteReferenceSemantics() {
        assertThat(references.rewrite("SUM('detail'!$B$2:$B$2)+SUM(tbl_credit[amount])+IF(\"B2:B2\"=\"B2:B2\",1,0)", "Summary", false, 0, 0))
            .isEqualTo("SUM('detail'!$B$2:$B$5)+SUM(tbl_credit[amount])+IF(\"B2:B2\"=\"B2:B2\",1,0)");
        assertThat(references.rewrite("$B2*C$1+$D$3+SUM(B2:C2)", "Detail", true, 2, 1))
            .isEqualTo("$B4*D$1+$D$6+SUM(C4:D4)");
        assertThat(references.rewrite("LOG10(B2)+1E5+SUM(B:B)+SUM(3:1048576)", "Detail", false, 0, 0))
            .isEqualTo("LOG10(B2)+1E5+SUM(B:B)+SUM(6:1048576)");
    }
    @Test void handlesQuotedSheetNamesWithEscapedApostrophesAndUnionRanges() {
        var references = new ExcelFormulaReferences(List.of(), List.of(new ExcelFormulaReferences.Detail("O'Brien", 1, 0, false, 3)));
        assertThat(references.rewrite("SUM('O''Brien'!A2:A2,'O''Brien'!$A$2:$A$2)", "汇总", false, 0, 0))
            .isEqualTo("SUM('O''Brien'!A2:A4,'O''Brien'!$A$2:$A$4)");
    }
}
