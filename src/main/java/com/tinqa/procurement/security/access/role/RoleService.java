package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.service.StaffNames;
import com.tinqa.procurement.security.access.AccessDTOs;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.Designation;
import com.tinqa.procurement.security.access.DesignationAccess;
import com.tinqa.procurement.security.access.PermissionGroup;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Custom roles: designations an admin builds from the permission groups they hold themselves. Built-in designations
 * are listed next to them and are read-only. Changes to a role's groups apply to its employees on their next request,
 * because access is resolved from the database every time.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final int CODE_MAX = 60;

    private final CustomRoleRepository roleRepository;
    private final AccessResolver accessResolver;
    private final CurrentUserProvider currentUserProvider;
    private final StaffNames staffNames;
    private final Clock clock;

    /** The access catalog, with the active custom roles after the built-in designations. */
    @Transactional(readOnly = true)
    public AccessDTOs.Catalog catalog() {
        return AccessDTOs.Catalog.build(roleRepository.findActiveWithGroups().stream()
                .map(role -> AccessDTOs.DesignationEntry.of(AccessResolver.grantOf(role), role.getDescription(), role.isActive()))
                .toList());
    }

    /** Every built-in designation and custom role, with how many employees hold it. Small by design, not paginated. */
    @Transactional(readOnly = true)
    public List<RoleDTOs.RoleView> list() {
        Map<String, Long> counts = new HashMap<>();
        roleRepository.countEmployeesByRole().forEach(row -> counts.put((String) row[0], ((Number) row[1]).longValue()));
        List<RoleDTOs.RoleView> roles = new ArrayList<>();
        for (Designation designation : Designation.values()) {
            roles.add(new RoleDTOs.RoleView(null, designation.name(), designation.label(), designation.department(),
                    designation.description(), DesignationAccess.groupsOf(designation).stream().sorted().map(Enum::name).toList(),
                    false, true, counts.getOrDefault(designation.name(), 0L), null, null));
        }
        List<CustomRole> custom = roleRepository.findAllWithGroups();
        Map<Long, String> names = staffNames.of(custom.stream().map(CustomRole::getCreatedBy).toList());
        custom.forEach(role -> roles.add(view(role, counts.getOrDefault("#" + role.getId(), 0L), names.get(role.getCreatedBy()))));
        return roles;
    }

    /** The groups the caller may put in a custom role: the ones they hold, except those never given to custom roles. */
    @Transactional(readOnly = true)
    public List<RoleDTOs.GrantableGroup> grantableGroups() {
        Set<PermissionGroup> grantable = grantableByCaller();
        return Arrays.stream(PermissionGroup.values()).filter(grantable::contains)
                .map(group -> new RoleDTOs.GrantableGroup(group.name(), group.label(), group.approvalLevel()))
                .toList();
    }

    @Transactional
    public RoleDTOs.RoleView create(RoleDTOs.RoleRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        String code = codeOf(request.label());
        if (Designation.fromCode(code).isPresent() || roleRepository.existsByCode(code)) {
            throw ApiErrors.conflict(ErrorCodes.ROLE_EXISTS, "A role with the code " + code + " already exists. Choose another name.");
        }
        Set<PermissionGroup> groups = requestedGroups(request.groups(), EnumSet.noneOf(PermissionGroup.class));
        Instant now = clock.instant();
        CustomRole role = CustomRole.builder()
                .code(code)
                .label(request.label().trim())
                .department(request.department().trim())
                .description(OptionalText.blankToNull(request.description()))
                .active(true)
                .groups(groups)
                .createdBy(actor.getId())
                .createdAt(now)
                .updatedBy(actor.getId())
                .updatedAt(now)
                .build();
        try {
            role = roleRepository.saveAndFlush(role);
        } catch (DataIntegrityViolationException exception) {
            throw ApiErrors.conflict(ErrorCodes.ROLE_EXISTS, "A role with the code " + code + " was just created. Choose another name.");
        }
        return view(role, 0, staffNames.of(List.of(actor.getId())).get(actor.getId()));
    }

    /** Changes a custom role. The code never changes; renaming the department moves its employees with it. */
    @Transactional
    public RoleDTOs.RoleView update(Long id, RoleDTOs.RoleRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        CustomRole role = roleRepository.findByIdForUpdate(id).orElseThrow(() -> roleNotFound(id));
        // Groups the role already has may stay even if the editor lacks them; new ones must be the editor's own
        Set<PermissionGroup> groups = requestedGroups(request.groups(), role.getGroups());
        String department = request.department().trim();
        boolean departmentChanged = !department.equals(role.getDepartment());
        role.setLabel(request.label().trim());
        role.setDepartment(department);
        role.setDescription(OptionalText.blankToNull(request.description()));
        if (request.active() != null) {
            role.setActive(request.active());
        }
        role.getGroups().clear();
        role.getGroups().addAll(groups);
        role.setUpdatedBy(actor.getId());
        role.setUpdatedAt(clock.instant());
        role = roleRepository.saveAndFlush(role);
        if (departmentChanged) {
            roleRepository.updateEmployeeDepartments(role.getId(), department);
        }
        long employees = roleRepository.countEmployeesByRole().stream()
                .filter(row -> ("#" + id).equals(row[0])).mapToLong(row -> ((Number) row[1]).longValue()).sum();
        return view(role, employees, staffNames.of(Collections.singletonList(role.getCreatedBy())).get(role.getCreatedBy()));
    }

    /** Deletes a custom role nobody holds; one in use can only be deactivated. */
    @Transactional
    public void delete(Long id) {
        CustomRole role = roleRepository.findByIdForUpdate(id).orElseThrow(() -> roleNotFound(id));
        if (roleRepository.isInUse(id)) {
            throw ApiErrors.conflict(ErrorCodes.ROLE_IN_USE, "Employees hold the role " + role.getLabel()
                    + ", so it can't be deleted. Deactivate it instead, or move them to another designation first.");
        }
        roleRepository.delete(role);
    }

    // ------------------------------------------------------------------ rules

    /** "Marketing Executive" becomes MARKETING_EXECUTIVE. */
    static String codeOf(String label) {
        String plain = Normalizer.normalize(label == null ? "" : label.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String code = plain.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (!code.isEmpty() && !Character.isLetter(code.charAt(0))) {
            code = "ROLE_" + code;
        }
        if (code.length() > CODE_MAX) {
            code = code.substring(0, CODE_MAX).replaceAll("_+$", "");
        }
        if (code.length() < 2) {
            throw ApiErrors.fieldError("label", "The name needs at least two letters or digits.");
        }
        return code;
    }

    private Set<PermissionGroup> requestedGroups(List<String> requested, Set<PermissionGroup> alreadyHeld) {
        Set<PermissionGroup> grantable = grantableByCaller();
        Set<PermissionGroup> groups = EnumSet.noneOf(PermissionGroup.class);
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (String code : requested == null ? List.<String>of() : requested) {
            PermissionGroup group = Arrays.stream(PermissionGroup.values())
                    .filter(value -> code != null && value.name().equalsIgnoreCase(code.trim())).findFirst().orElse(null);
            if (group == null) {
                errors.add(ApiErrors.error("groups", "Unknown permission group " + code + "."));
            } else if (group == PermissionGroup.SELF_SERVICE) {
                // Every employee has it anyway
                continue;
            } else if (DesignationAccess.NEVER_IN_CUSTOM_ROLES.contains(group)) {
                errors.add(ApiErrors.error("groups", group.label() + " can't be part of a custom role."));
            } else if (!grantable.contains(group) && !alreadyHeld.contains(group)) {
                throw ApiErrors.forbidden(ErrorCodes.ROLE_GROUP_NOT_GRANTABLE,
                        "You can only give a role permission groups you hold yourself (" + group.label() + ").");
            } else {
                groups.add(group);
            }
        }
        ApiErrors.throwIfAny(errors);
        if (groups.isEmpty()) {
            throw ApiErrors.fieldError("groups", "Pick at least one permission group besides self service.");
        }
        return groups;
    }

    private Set<PermissionGroup> grantableByCaller() {
        Set<PermissionGroup> held = EnumSet.noneOf(PermissionGroup.class);
        held.addAll(accessResolver.resolve(currentUserProvider.getCurrentUser()).groups());
        held.removeAll(DesignationAccess.NEVER_IN_CUSTOM_ROLES);
        held.remove(PermissionGroup.SELF_SERVICE);
        return held;
    }

    private static RoleDTOs.RoleView view(CustomRole role, long employeeCount, String createdByName) {
        return new RoleDTOs.RoleView(role.getId(), role.getCode(), role.getLabel(), role.getDepartment(), role.getDescription(),
                DesignationAccess.groupsOfCustomRole(AccessResolver.grantOf(role)).stream().sorted().map(Enum::name).toList(),
                true, role.isActive(), employeeCount, createdByName, role.getUpdatedAt());
    }

    private static ResourceNotFoundException roleNotFound(Long id) {
        return new ResourceNotFoundException("Custom role not found with id: " + id);
    }
}
