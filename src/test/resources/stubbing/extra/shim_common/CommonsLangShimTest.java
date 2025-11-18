package fixtures.shim_common;

import org.apache.commons.lang3.StringUtils;

class CommonsLangShimTest {
    @TargetMethod
    boolean checkString(String str) {
        boolean empty = StringUtils.isEmpty(str);
        boolean notEmpty = StringUtils.isNotEmpty(str);
        boolean blank = StringUtils.isBlank(str);
        boolean notBlank = StringUtils.isNotBlank(str);
        String trimmed = StringUtils.trim(str);
        return !empty && notBlank;
    }
}

