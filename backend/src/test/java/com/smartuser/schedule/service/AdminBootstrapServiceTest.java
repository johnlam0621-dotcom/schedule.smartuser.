package com.smartuser.schedule.service;

import com.smartuser.schedule.mapper.UserMapper;
import com.smartuser.schedule.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapServiceTest {
  @Test
  void doesNothingWithoutExplicitPassword() throws Exception {
    UserMapper mapper = mock(UserMapper.class);
    PasswordService passwordService = mock(PasswordService.class);
    AdminBootstrapService service = new AdminBootstrapService(mapper, passwordService, "admin", "", "System Admin");

    service.run(new DefaultApplicationArguments(new String[0]));

    verify(mapper, never()).insert(any(UserAccount.class));
  }

  @Test
  void createsEnabledAdminWithEncodedPassword() throws Exception {
    UserMapper mapper = mock(UserMapper.class);
    PasswordService passwordService = mock(PasswordService.class);
    when(mapper.selectOne(any())).thenReturn(null);
    when(passwordService.encode("StrongPassword!2026")).thenReturn("bcrypt-hash");
    AdminBootstrapService service = new AdminBootstrapService(
        mapper, passwordService, "admin", "StrongPassword!2026", "System Admin");

    service.run(new DefaultApplicationArguments(new String[0]));

    ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
    verify(mapper).insert(account.capture());
    assertThat(account.getValue().getUsername()).isEqualTo("admin");
    assertThat(account.getValue().getRoleCode()).isEqualTo("admin");
    assertThat(account.getValue().getStatus()).isEqualTo(1);
    assertThat(account.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
  }
}
