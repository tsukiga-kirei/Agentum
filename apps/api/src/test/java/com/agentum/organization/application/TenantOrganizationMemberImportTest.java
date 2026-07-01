package com.agentum.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.TenantOrgRoleRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.organization.interfaces.MemberImportResultResponse;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TenantOrganizationMemberImportTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private UserRoleAssignmentRepository userRoleAssignmentRepository;
    @Mock
    private UserMembershipRepository userMembershipRepository;
    @Mock
    private UserMembershipRoleRepository userMembershipRoleRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PageGrantRepository pageGrantRepository;
    @Mock
    private ResourceGrantRepository resourceGrantRepository;
    @Mock
    private TenantOrgRoleRepository tenantOrgRoleRepository;
    @Mock
    private TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    @Mock
    private SystemCapabilityRepository systemCapabilityRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldImportMemberIntoDefaultDepartmentWithoutRole() {
        TenantOrganizationService service = newService();
        DepartmentEntity defaultDepartment = DepartmentEntity.create(TENANT_ID, null, "默认部门", "default", 0);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(OptionalTenant.active());
        when(departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(TENANT_ID, "active")).thenReturn(List.of(defaultDepartment));
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of());
        when(userAccountRepository.existsByUsername("zhangsan")).thenReturn(false);
        when(userAccountRepository.existsByEmailIgnoreCase("zhangsan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("agentum123")).thenReturn("hash");

        byte[] workbook = workbookBytes(new String[][] {
            { "张三", "zhangsan", "", "", "zhangsan@example.com" },
        });
        MemberImportResultResponse result = service.importMembers(TENANT_ID, OPERATOR_USER_ID, new ByteArrayInputStream(workbook), workbook.length);

        ArgumentCaptor<UserMembershipEntity> membershipCaptor = ArgumentCaptor.forClass(UserMembershipEntity.class);
        verify(userMembershipRepository).save(membershipCaptor.capture());
        verify(userMembershipRoleRepository, never()).save(any(UserMembershipRoleEntity.class));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failedRows()).isEmpty();
        assertThat(membershipCaptor.getValue().getDepartmentId()).isEqualTo(defaultDepartment.getId());
    }

    @Test
    void shouldRejectDuplicateUsernameAndEmailWithinImportFile() {
        TenantOrganizationService service = newService();
        DepartmentEntity defaultDepartment = DepartmentEntity.create(TENANT_ID, null, "默认部门", "default", 0);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(OptionalTenant.active());
        when(departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(TENANT_ID, "active")).thenReturn(List.of(defaultDepartment));
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of());

        byte[] workbook = workbookBytes(new String[][] {
            { "张三", "zhangsan", "", "", "same@example.com" },
            { "李四", "zhangsan", "", "", "same@example.com" },
        });
        MemberImportResultResponse result = service.importMembers(TENANT_ID, OPERATOR_USER_ID, new ByteArrayInputStream(workbook), workbook.length);

        verify(userMembershipRepository, never()).save(any(UserMembershipEntity.class));
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isZero();
        assertThat(result.failedRows()).hasSize(2);
        assertThat(result.failedRows().get(0).reason()).contains("用户名在导入文件中重复", "邮箱在导入文件中重复");
    }

    private TenantOrganizationService newService() {
        return new TenantOrganizationService(
            tenantRepository,
            userAccountRepository,
            userRoleAssignmentRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            departmentRepository,
            roleRepository,
            pageGrantRepository,
            resourceGrantRepository,
            tenantOrgRoleRepository,
            tenantCapabilityGrantRepository,
            systemCapabilityRepository,
            passwordEncoder,
            new ObjectMapper()
        );
    }

    private static byte[] workbookBytes(String[][] rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("成员");
            Row header = sheet.createRow(0);
            String[] headers = { "姓名", "用户名", "部门", "角色", "邮箱" };
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                for (int cellIndex = 0; cellIndex < rows[rowIndex].length; cellIndex++) {
                    row.createCell(cellIndex).setCellValue(rows[rowIndex][cellIndex]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class OptionalTenant {
        static java.util.Optional<TenantEntity> active() {
            return java.util.Optional.of(TenantEntity.create("演示租户", "demo", Instant.now()));
        }
    }
}
