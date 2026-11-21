package com.sf.release.resource;

import com.sf.release.application.SubsystemAppService;
import com.sf.release.application.SubsystemAppService.CreateCmd;
import com.sf.release.application.SubsystemAppService.PageQuery;
import com.sf.release.application.SubsystemAppService.PageResult;
import com.sf.release.application.SubsystemAppService.SubsystemView;
import com.sf.release.application.SubsystemAppService.UpdateCmd;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subsystem management against the real test schema (Flyway clean + migrate, MyBatis-Plus
 * mappers, MySQL 8). The test profile keeps the dev schema untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubsystemServiceTest {

    private static final String OWNER = "01388007";
    private static final String AGENT = "01399596";
    private static final String INACTIVE = "01399999";
    private static final int PARAM_INVALID_CODE = 40000;
    private static final int DUPLICATE_NAME_CODE = 40010;

    @Autowired SubsystemAppService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", OWNER, AGENT, INACTIVE);
        givenUser(OWNER, "ACTIVE");
        givenUser(AGENT, "ACTIVE");
        givenUser(INACTIVE, "INACTIVE");
    }

    @Test
    void create_trims_and_rejects_duplicate_name() {
        long id = service.create(new CreateCmd(" SCC-PORTAL-CORE-OS ", OWNER, AGENT));

        assertThat(service.get(id).name()).isEqualTo("SCC-PORTAL-CORE-OS");
        assertThat(service.get(id).ownerId()).isEqualTo(OWNER);
        assertThat(service.get(id).agentId()).isEqualTo(AGENT);
        assertThat(service.get(id).status()).isEqualTo("ACTIVE");

        // The duplicate check runs before the employee lookup, so a resubmitted name is
        // reported as a duplicate even though "1" is not a known 工号.
        assertThatThrownBy(() -> service.create(new CreateCmd("SCC-PORTAL-CORE-OS", "1", null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(DUPLICATE_NAME_CODE));

        // A duplicate after trimming is still the same name.
        assertThatThrownBy(() -> service.create(new CreateCmd("  SCC-PORTAL-CORE-OS  ", OWNER, null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME);

        assertThat(service.page(new PageQuery(1, 10, null)).total()).isEqualTo(1);
    }

    @Test
    void blank_name_is_param_invalid() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.create(new CreateCmd(blank, OWNER, null)))
                .as("blank name %s is rejected", blank)
                .isInstanceOf(BizException.class)
                .extracting("error").isEqualTo(BizError.PARAM_INVALID)
                .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(PARAM_INVALID_CODE));
        }

        assertThatThrownBy(() -> service.create(new CreateCmd("X".repeat(129), OWNER, null)))
            .as("names longer than the VARCHAR(128) column are rejected")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThat(service.page(new PageQuery(1, 10, null)).total()).isZero();
    }

    @Test
    void owner_and_agent_must_exist() {
        assertThatThrownBy(() -> service.create(new CreateCmd("SCC-OWNER-UNKNOWN", "99999999", null)))
            .as("unknown owner 工号")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(PARAM_INVALID_CODE));

        assertThatThrownBy(() -> service.create(new CreateCmd("SCC-AGENT-UNKNOWN", OWNER, "99999999")))
            .as("unknown agent 工号")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> service.create(new CreateCmd("SCC-OWNER-INACTIVE", INACTIVE, null)))
            .as("an inactive owner is not a valid owner")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> service.create(new CreateCmd("SCC-OWNER-BLANK", "  ", null)))
            .as("a blank owner is a parameter error, not a missing employee error")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThat(service.page(new PageQuery(1, 10, "SCC-")).total()).isZero();
    }

    @Test
    void get_returns_view_with_trimmed_name() {
        long id = service.create(new CreateCmd(" SCC-PORTAL-CORE-OS ", OWNER, AGENT,
            " 核心门户 ", " git@git.sf:scc/portal.git ", " SCC-PORTAL "));

        SubsystemView view = service.get(id);
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.name()).isEqualTo("SCC-PORTAL-CORE-OS");
        assertThat(view.ownerId()).isEqualTo(OWNER);
        assertThat(view.agentId()).isEqualTo(AGENT);
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.description()).isEqualTo("核心门户");
        assertThat(view.gitUrl()).isEqualTo("git@git.sf:scc/portal.git");
        assertThat(view.codingProject()).isEqualTo("SCC-PORTAL");
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.updatedAt()).isNotNull();
        // Instant <-> DATETIME(3) UTC round-trip: a timezone bug would shift these by hours.
        Instant now = Instant.now();
        assertThat(view.createdAt()).isBetween(now.minusSeconds(60), now.plusSeconds(60));
        assertThat(view.updatedAt()).isBetween(now.minusSeconds(60), now.plusSeconds(60));

        assertThatThrownBy(() -> service.get(id + 1000))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void page_filters_by_keyword_and_pages() {
        service.create(new CreateCmd("SCC-PORTAL-CORE-OS", OWNER, AGENT));
        service.create(new CreateCmd("SCC-PORTAL-WEB", OWNER, AGENT));
        service.create(new CreateCmd("SCC-ORDER-API", OWNER, null));

        PageResult<SubsystemView> all = service.page(new PageQuery(1, 10, null));
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.records()).hasSize(3);
        assertThat(all.records()).extracting(SubsystemView::name)
            .contains("SCC-PORTAL-CORE-OS", "SCC-PORTAL-WEB", "SCC-ORDER-API");

        PageResult<SubsystemView> portal = service.page(new PageQuery(1, 10, "PORTAL"));
        assertThat(portal.total()).isEqualTo(2);
        assertThat(portal.records()).extracting(SubsystemView::name)
            .containsExactlyInAnyOrder("SCC-PORTAL-CORE-OS", "SCC-PORTAL-WEB");

        assertThat(service.page(new PageQuery(1, 10, "   ")).total())
            .as("blank keyword is no filter")
            .isEqualTo(3);

        assertThat(service.page(new PageQuery(1, 10, "NOT-EXIST")).total()).isZero();
        assertThat(service.page(new PageQuery(1, 10, "NOT-EXIST")).records()).isEmpty();

        PageResult<SubsystemView> first = service.page(new PageQuery(1, 1, "SCC"));
        PageResult<SubsystemView> second = service.page(new PageQuery(2, 1, "SCC"));
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.pageNo()).isEqualTo(1);
        assertThat(first.pageSize()).isEqualTo(1);
        assertThat(first.records()).hasSize(1);
        assertThat(second.records()).hasSize(1);
        assertThat(first.records().get(0).name())
            .isNotEqualTo(second.records().get(0).name());

        assertThatThrownBy(() -> service.page(new PageQuery(0, 10, null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> service.page(new PageQuery(1, 0, null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> service.page(new PageQuery(1, 101, null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void update_trims_and_rejects_duplicate_name() {
        service.create(new CreateCmd("SCC-PORTAL-CORE-OS", OWNER, AGENT));
        long web = service.create(new CreateCmd("SCC-PORTAL-WEB", OWNER, null));

        service.update(web, new UpdateCmd(" SCC-PORTAL-WEB-V2 ", OWNER, AGENT, "INACTIVE",
            " web ", " git@git.sf:scc/web.git ", " SCC-PORTAL "));

        SubsystemView updated = service.get(web);
        assertThat(updated.name()).isEqualTo("SCC-PORTAL-WEB-V2");
        assertThat(updated.agentId()).isEqualTo(AGENT);
        assertThat(updated.status()).isEqualTo("INACTIVE");
        assertThat(updated.description()).isEqualTo("web");
        assertThat(updated.gitUrl()).isEqualTo("git@git.sf:scc/web.git");

        // Updating without a rename must not trip the uniqueness check.
        assertThatCode(() -> service.update(web, new UpdateCmd("SCC-PORTAL-WEB-V2", OWNER, AGENT)))
            .doesNotThrowAnyException();

        // A null nullable field on update clears it.
        service.update(web, new UpdateCmd("SCC-PORTAL-WEB-V2", OWNER, AGENT, null, null, null, null));
        assertThat(service.get(web).description()).isNull();
        assertThat(service.get(web).gitUrl()).isNull();

        assertThatThrownBy(() -> service.update(web, new UpdateCmd("SCC-PORTAL-CORE-OS", OWNER, AGENT)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME);

        assertThatThrownBy(() -> service.update(web, new UpdateCmd("SCC-PORTAL-WEB-V3", "99999999", null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> service.update(999_999L, new UpdateCmd("SCC-NEW", OWNER, null)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> service.update(web, new UpdateCmd("SCC-PORTAL-WEB-V3", OWNER, null, "BROKEN")))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        // None of the rejected updates changed the stored row.
        assertThat(service.get(web).name()).isEqualTo("SCC-PORTAL-WEB-V2");
        assertThat(service.get(web).status()).isEqualTo("INACTIVE");
    }

    private void givenUser(String userId, String status) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, ?, NOW(3))",
            userId, "用户" + userId, status);
    }
}