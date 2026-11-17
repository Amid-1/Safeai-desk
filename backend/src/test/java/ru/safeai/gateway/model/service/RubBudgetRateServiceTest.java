package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ApiException;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.domain.RubBudgetSnapshot;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.RubBudgetInput;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RubBudgetRateServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate EFFECTIVE = DAY.minusDays(1);
    private static final Instant NOW = Instant.parse("2026-11-17T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final BigDecimal RATE = new BigDecimal("90.0000");

    private static CreateOrganizationModelPolicyVersionRequest request(String max, String monthly,
            String rate, LocalDate date, BigDecimal usd) {
        return new CreateOrganizationModelPolicyVersionRequest(0, true, Set.of(), Set.of(), null,
                null, null, usd, null, BudgetEnforcement.HARD, false, false, false,
                new RubBudgetInput(max == null ? null : new BigDecimal(max),
                        monthly == null ? null : new BigDecimal(monthly), new BigDecimal(rate), date, EFFECTIVE));
    }

    @Test void officialRateCachedAndConversionStrictlyRoundsDown() {
        AtomicInteger calls = new AtomicInteger();
        var service = new RubBudgetRateService(CLOCK, day -> {
            calls.incrementAndGet();
            assertThat(day).isEqualTo(DAY);
            return new CbrUsdRateSource.Rate(EFFECTIVE, RATE);
        });
        assertThat(service.current().rubPerUsd()).isEqualTo("90.0000");
        var snapshot = service.resolve(request("5", "10000", "90", DAY, null), null);
        assertThat(snapshot.maxRequestCostUsd()).isEqualByComparingTo("0.055555555555");
        assertThat(snapshot.monthlyBudgetUsd()).isEqualByComparingTo("111.111111111111");
        var zero = service.resolve(request("0", null, "90", DAY, null), null);
        assertThat(zero.maxRequestCostUsd()).isEqualByComparingTo("0");
        assertThat(zero.monthlyBudgetUsd()).isNull();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(RubBudgetSnapshot.toUsd(new BigDecimal("0.000000000001"), RATE)).isZero();
        assertThatThrownBy(() -> service.resolve(request("5", null, "91", DAY, null), null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.resolve(request("5", null, "90", DAY.minusDays(1), null), null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.resolve(request("5", null, "90", DAY, BigDecimal.ONE), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requireCurrentIfChanged(snapshot, null, NOW.plusSeconds(86400)))
                .isInstanceOf(ConflictException.class);
    }

    @Test void outageFailsClosedWithoutInventingRateAndHasCooldown() {
        AtomicInteger calls = new AtomicInteger();
        var service = new RubBudgetRateService(CLOCK, day -> {
            calls.incrementAndGet(); throw new IOException("unavailable");
        });
        assertThatThrownBy(service::current).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resolve(request("5", null, "90", DAY, null), null))
                .isInstanceOf(ApiException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void malformedBudgetsAreRejectedBeforeAnyExternalRateLookup() {
        var service = new RubBudgetRateService(CLOCK, day -> {
            throw new AssertionError("Invalid input must not trigger an external request");
        });
        for (String rate : new String[]{"0", "-1", "0.000000001", "10000000000"}) {
            assertThatThrownBy(() -> service.resolve(request("5", null, rate, DAY, null), null))
                    .isInstanceOf(BadRequestException.class);
        }
        for (String amount : new String[]{"-0.000000000001", "0.0000000000001",
                "1000000000000000000"}) {
            assertThatThrownBy(() -> service.resolve(request(amount, null, "90", DAY, null), null))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.resolve(request(null, amount, "90", DAY, null), null))
                    .isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> service.resolve(request(null, null, "90", DAY, null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void validRubAmountWithUnrepresentableUsdConversionFailsClosed() {
        BigDecimal tinyRate = new BigDecimal("0.00000001");
        var service = new RubBudgetRateService(CLOCK,
                day -> new CbrUsdRateSource.Rate(EFFECTIVE, tinyRate));
        String largestAmount = "999999999999999999.999999999999";
        assertThatThrownBy(() -> service.resolve(
                request(largestAmount, null, tinyRate.toPlainString(), DAY, null), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.resolve(
                request(null, largestAmount, tinyRate.toPlainString(), DAY, null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void unchangedHistoricalBudgetDoesNotNeedExternalSource() {
        var saved = new RubBudgetSnapshot(new BigDecimal("5"), null, RATE, DAY.minusDays(1),
                EFFECTIVE, NOW.minusSeconds(86400));
        var previous = new OrganizationModelPolicy(UUID.randomUUID(), UUID.randomUUID(), 1, true,
                Set.of(), Set.of(), null, null, null, saved.maxRequestCostUsd(), null,
                BudgetEnforcement.HARD, false, false, false, UUID.randomUUID(), NOW.minusSeconds(86400), saved);
        var service = new RubBudgetRateService(CLOCK, day -> {throw new AssertionError("No network needed");});
        assertThat(service.resolve(request("5.00", null, "90", DAY.minusDays(1), null), previous)).isSameAs(saved);
        assertThatThrownBy(() -> service.resolve(request("6", null, "90", DAY.minusDays(1), null), previous))
                .isInstanceOf(ConflictException.class);
        service.requireCurrentIfChanged(saved, previous, NOW);
    }

    @Test void parserRejectsUntrustedXmlAndInvalidUsd() throws Exception {
        String valid = "<ValCurs Date=\"16.11.2026\"><Valute><NumCode>840</NumCode>"
                + "<CharCode>USD</CharCode><Nominal>1</Nominal><Value>90,0000</Value></Valute></ValCurs>";
        assertThat(CbrUsdRateSource.parse(valid.getBytes(StandardCharsets.UTF_8), DAY).rubPerUsd())
                .isEqualByComparingTo(RATE);
        for (String bad : new String[] {"<!DOCTYPE ValCurs SYSTEM 'file:///etc/passwd'>" + valid,
                valid.replace("</Valute>", ""),
                "<!DOCTYPE ValCurs [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                        + valid.replace("90,0000", "&xxe;"),
                valid.replace("16.11.2026", "18.11.2026"), valid.replace("16.11.2026", "01.10.2026"),
                valid.replace("840", "999"), valid.replace("90,0000", "0,0000"),
                valid.replace("<Nominal>1", "<Nominal>2")}) {
            assertThatThrownBy(() -> CbrUsdRateSource.parse(bad.getBytes(StandardCharsets.UTF_8), DAY))
                    .isInstanceOf(IOException.class);
        }
    }
}
