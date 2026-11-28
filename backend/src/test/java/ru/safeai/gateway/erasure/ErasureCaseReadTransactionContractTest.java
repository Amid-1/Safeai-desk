package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Shared PostgreSQL role locks need a transaction capable of SELECT FOR SHARE. */
class ErasureCaseReadTransactionContractTest {

    @Test
    void roleProtectedReadsMustAllowRowLocks() throws NoSuchMethodException {
        assertLockCapable("mine", SafeAiUserPrincipal.class);
        assertLockCapable("tenantQueue", SafeAiUserPrincipal.class);
        assertLockCapable("get", UUID.class, SafeAiUserPrincipal.class);
    }

    private static void assertLockCapable(String methodName, Class<?>... argumentTypes)
            throws NoSuchMethodException {
        Method method = ErasureCaseService.class.getDeclaredMethod(methodName, argumentTypes);
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(transaction, "Method must retain a transaction: " + methodName);
        assertFalse(transaction.readOnly(), "SELECT FOR SHARE requires readOnly=false: " + methodName);
    }
}
