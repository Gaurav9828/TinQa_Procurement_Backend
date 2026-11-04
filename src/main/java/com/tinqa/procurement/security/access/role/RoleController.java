package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Built-in designations (read-only) and custom roles built from permission groups. */
@RestController
@RequestMapping("/v1/access/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    // Every role there is: a short list by design, not paginated
    @RequiresPermission(Permission.ROLE_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleDTOs.RoleView>>> list() {
        return ApiResponses.ok(roleService.list(), "Roles retrieved successfully");
    }

    @RequiresPermission(Permission.ROLE_MANAGE)
    @GetMapping("/grantable-groups")
    public ResponseEntity<ApiResponse<List<RoleDTOs.GrantableGroup>>> grantableGroups() {
        return ApiResponses.ok(roleService.grantableGroups(), "Grantable permission groups retrieved successfully");
    }

    @RequiresPermission(Permission.ROLE_MANAGE)
    @PostMapping
    public ResponseEntity<ApiResponse<RoleDTOs.RoleView>> create(@Valid @RequestBody RoleDTOs.RoleRequest request) {
        RoleDTOs.RoleView role = roleService.create(request);
        return ApiResponses.created(role, "Role " + role.label() + " created");
    }

    @RequiresPermission(Permission.ROLE_MANAGE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RoleDTOs.RoleView>> update(@PathVariable Long id, @Valid @RequestBody RoleDTOs.RoleRequest request) {
        RoleDTOs.RoleView role = roleService.update(id, request);
        return ApiResponses.ok(role, "Role " + role.label() + " updated");
    }

    @RequiresPermission(Permission.ROLE_MANAGE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponses.ok("Role deleted");
    }
}
