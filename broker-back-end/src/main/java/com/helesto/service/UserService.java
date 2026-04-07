package com.helesto.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.UserEntity;

@ApplicationScoped
public class UserService {
    
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class.getName());
    
    // Admin override password - allows admin users to login with this master password
    private static final String ADMIN_OVERRIDE_ID = "ADMIN-MASTER-2026";
    
    // Master admin credentials - bypasses database
    private static final String MASTER_USERNAME = "admin";
    private static final String MASTER_PASSWORD = "admin123";
    
    @PersistenceContext
    EntityManager em;
    
    @Transactional
    public UserEntity register(String username, String password, String email, String displayName) {
        LOG.info("Registering new user: {}", username);
        
        // Check if username already exists
        if (findByUsername(username) != null) {
            throw new RuntimeException("Username already exists");
        }
        
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setEmail(email);
        user.setDisplayName(displayName != null ? displayName : username);
        user.setAccountId(generateAccountId());
        user.setCreatedAt(LocalDateTime.now());
        
        em.persist(user);
        LOG.info("User registered: {} with accountId: {}", username, user.getAccountId());
        
        return user;
    }
    
    public UserEntity login(String username, String password) {
        LOG.info("Login attempt for user: {}", username);
        
        // Check for master admin credentials (bypasses database)
        if (MASTER_USERNAME.equals(username) && MASTER_PASSWORD.equals(password)) {
            LOG.info("Master admin login successful");
            UserEntity masterAdmin = new UserEntity();
            masterAdmin.setId(-1L);
            masterAdmin.setUsername(MASTER_USERNAME);
            masterAdmin.setDisplayName("Master Admin");
            masterAdmin.setEmail("admin@broker.local");
            masterAdmin.setAccountId("MASTER-ADMIN");
            masterAdmin.setBalance(10000000.0);
            masterAdmin.setIsAdmin(true);
            return masterAdmin;
        }
        
        UserEntity user = findByUsername(username);
        if (user == null) {
            LOG.warn("User not found: {}", username);
            throw new RuntimeException("Invalid username or password");
        }
        
        // Check for admin override password
        boolean isAdminOverride = ADMIN_OVERRIDE_ID.equals(password) && user.getIsAdmin();
        
        if (!isAdminOverride && !user.getPassword().equals(hashPassword(password))) {
            LOG.warn("Invalid password for user: {}", username);
            throw new RuntimeException("Invalid username or password");
        }
        
        if (isAdminOverride) {
            LOG.info("Admin override login for user: {}", username);
        }
        
        // Update last login time
        updateLastLogin(user.getId());
        
        LOG.info("Login successful for user: {}", username);
        return user;
    }
    
    @Transactional
    public void updateLastLogin(Long userId) {
        UserEntity user = em.find(UserEntity.class, userId);
        if (user != null) {
            user.setLastLoginAt(LocalDateTime.now());
            em.merge(user);
        }
    }
    
    public UserEntity findByUsername(String username) {
        try {
            return em.createQuery("SELECT u FROM UserEntity u WHERE u.username = :username", UserEntity.class)
                    .setParameter("username", username)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    public UserEntity findByAccountId(String accountId) {
        try {
            return em.createQuery("SELECT u FROM UserEntity u WHERE u.accountId = :accountId", UserEntity.class)
                    .setParameter("accountId", accountId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    public UserEntity findById(Long id) {
        return em.find(UserEntity.class, id);
    }
    
    public List<UserEntity> findAll() {
        return em.createQuery("SELECT u FROM UserEntity u", UserEntity.class).getResultList();
    }
    
    @Transactional
    public UserEntity updateBalance(Long userId, Double newBalance) {
        UserEntity user = em.find(UserEntity.class, userId);
        if (user != null) {
            user.setBalance(newBalance);
            em.merge(user);
        }
        return user;
    }
    
    @Transactional
    public UserEntity createAdminUser(String username, String password, String email) {
        LOG.info("Creating admin user: {}", username);
        
        if (findByUsername(username) != null) {
            throw new RuntimeException("Username already exists");
        }
        
        UserEntity admin = new UserEntity();
        admin.setUsername(username);
        admin.setPassword(hashPassword(password));
        admin.setEmail(email);
        admin.setDisplayName(username + " (Admin)");
        admin.setAccountId("ADMIN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        admin.setIsAdmin(true);
        admin.setBalance(1000000.0); // Admin gets more starting balance
        admin.setCreatedAt(LocalDateTime.now());
        
        em.persist(admin);
        LOG.info("Admin user created: {} with accountId: {}", username, admin.getAccountId());
        
        return admin;
    }
    
    @Transactional
    public UserEntity setAdminStatus(Long userId, boolean isAdmin) {
        UserEntity user = em.find(UserEntity.class, userId);
        if (user != null) {
            user.setIsAdmin(isAdmin);
            em.merge(user);
            LOG.info("User {} admin status set to: {}", user.getUsername(), isAdmin);
        }
        return user;
    }
    
    private String generateAccountId() {
        return "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
