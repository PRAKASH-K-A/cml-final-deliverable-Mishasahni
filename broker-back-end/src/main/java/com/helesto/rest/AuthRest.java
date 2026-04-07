package com.helesto.rest;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.UserEntity;
import com.helesto.service.UserService;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthRest {
    
    private static final Logger LOG = LoggerFactory.getLogger(AuthRest.class.getName());
    
    @Inject
    UserService userService;
    
    @POST
    @Path("/register")
    @Operation(summary = "Register a new user", description = "Create a new user account with unique accountId")
    @APIResponse(responseCode = "201", description = "User registered", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @APIResponse(responseCode = "400", description = "Registration failed")
    public Response register(RegisterRequest request) {
        LOG.info("POST /api/auth/register - username: {}", request.username);
        
        try {
            UserEntity user = userService.register(
                request.username,
                request.password,
                request.email,
                request.displayName
            );
            
            return Response.status(Response.Status.CREATED)
                    .entity(toUserResponse(user))
                    .build();
        } catch (Exception e) {
            LOG.error("Registration failed", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
    
    @POST
    @Path("/login")
    @Operation(summary = "Login user", description = "Authenticate a user and return their profile")
    @APIResponse(responseCode = "200", description = "Login successful", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @APIResponse(responseCode = "401", description = "Invalid credentials")
    public Response login(LoginRequest request) {
        LOG.info("POST /api/auth/login - username: {}", request.username);
        
        try {
            UserEntity user = userService.login(request.username, request.password);
            
            return Response.ok()
                    .entity(toUserResponse(user))
                    .build();
        } catch (Exception e) {
            LOG.error("Login failed", e);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
    
    @GET
    @Path("/user/{username}")
    @Operation(summary = "Get user by username", description = "Retrieve user profile by username")
    @APIResponse(responseCode = "200", description = "User found", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @APIResponse(responseCode = "404", description = "User not found")
    public Response getUser(@PathParam("username") String username) {
        LOG.info("GET /api/auth/user/{}", username);
        
        UserEntity user = userService.findByUsername(username);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("User not found"))
                    .build();
        }
        
        return Response.ok().entity(toUserResponse(user)).build();
    }
    
    @POST
    @Path("/admin/create")
    @Operation(summary = "Create admin user", description = "Create a new admin user (requires admin override key)")
    @APIResponse(responseCode = "201", description = "Admin created", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @APIResponse(responseCode = "403", description = "Invalid admin key")
    public Response createAdmin(AdminCreateRequest request) {
        LOG.info("POST /api/auth/admin/create - username: {}", request.username);
        
        // Verify admin override key
        if (!"ADMIN-MASTER-2026".equals(request.adminKey)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Invalid admin key"))
                    .build();
        }
        
        try {
            UserEntity admin = userService.createAdminUser(
                request.username,
                request.password,
                request.email
            );
            
            return Response.status(Response.Status.CREATED)
                    .entity(toUserResponse(admin))
                    .build();
        } catch (Exception e) {
            LOG.error("Admin creation failed", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }
    
    private UserResponse toUserResponse(UserEntity user) {
        UserResponse response = new UserResponse();
        response.id = user.getId();
        response.username = user.getUsername();
        response.email = user.getEmail();
        response.displayName = user.getDisplayName();
        response.accountId = user.getAccountId();
        response.balance = user.getBalance();
        response.isAdmin = user.getIsAdmin();
        return response;
    }
    
    // Request/Response classes
    
    public static class RegisterRequest {
        public String username;
        public String password;
        public String email;
        public String displayName;
    }
    
    public static class LoginRequest {
        public String username;
        public String password;
    }
    
    public static class AdminCreateRequest {
        public String username;
        public String password;
        public String email;
        public String adminKey;
    }
    
    public static class UserResponse {
        public Long id;
        public String username;
        public String email;
        public String displayName;
        public String accountId;
        public Double balance;
        public Boolean isAdmin;
    }
    
    public static class ErrorResponse {
        public String error;
        
        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
