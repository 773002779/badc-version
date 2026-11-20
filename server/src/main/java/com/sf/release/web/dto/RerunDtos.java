package com.sf.release.web.dto;

/**
 * Request bodies of the 重新发布 endpoint. All fields are optional so the confirmation can be
 * omitted; the service rejects a call that would revoke the test pass without it.
 */
public final class RerunDtos {

    private RerunDtos() {
    }

    /**
     * @param confirmRevokeTestPass explicit confirmation that a changed commit may revoke the
     *                              previous 「测试通过」 (requirements §4.5: 二次确认 + 留痕)
     */
    public record RerunRequest(Boolean confirmRevokeTestPass) {
    }
}
