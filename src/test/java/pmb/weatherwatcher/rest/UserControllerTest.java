package pmb.weatherwatcher.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import pmb.weatherwatcher.TestUtils;
import pmb.weatherwatcher.dto.user.JwtTokenDto;
import pmb.weatherwatcher.dto.user.PasswordDto;
import pmb.weatherwatcher.dto.user.UserDto;
import pmb.weatherwatcher.exception.AlreadyExistException;
import pmb.weatherwatcher.security.JwtTokenProvider;
import pmb.weatherwatcher.security.MyUserDetailsService;
import pmb.weatherwatcher.service.UserService;

@ActiveProfiles("test")
@Import(JwtTokenProvider.class)
@MockBean(MyUserDetailsService.class)
@WebMvcTest(controllers = UserController.class)
@DisplayNameGeneration(value = ReplaceUnderscores.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private UserService userService;
    private static final UserDto DUMMY_USER = new UserDto("test", "password", "lyon");
    private static final PasswordDto DUMMY_PASSWORD = new PasswordDto("password", "password2");

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(userService);
    }

    @Nested
    class Signin {

        @Test
        void ok() throws Exception {
            JwtTokenDto expected = new JwtTokenDto("jwtToken");
            ArgumentCaptor<UserDto> login = ArgumentCaptor.forClass(UserDto.class);

            when(userService.login(any())).thenReturn(expected);

            assertEquals(expected.getToken(),
                    objectMapper
                            .readValue(TestUtils.readResponse
                                    .apply(mockMvc.perform(post("/users/signin").content(objectMapper.writeValueAsString(DUMMY_USER))
                                            .contentType(MediaType.APPLICATION_JSON_VALUE)).andExpect(status().isOk())),
                                    JwtTokenDto.class)
                            .getToken());

            verify(userService).login(login.capture());

            UserDto signin = login.getValue();
            assertAll(() -> assertEquals("test", signin.getUsername()), () -> assertEquals("password", signin.getPassword()),
                    () -> assertEquals("lyon", signin.getFavouriteLocation()));
        }

        @ParameterizedTest(name = "Given user with login ''{0}'' and password ''{1}'' when login then bad request")
        @CsvSource({ ", password", "o, password", "test,", "test, p", "01234567891011121314151617181920, password",
                "test, 01234567891011121314151617181920" })
        void when_failed_validation_then_bad_request(String login, String password) throws Exception {
            mockMvc.perform(post("/users/signin").content(objectMapper.writeValueAsString(new UserDto(login, password, null)))
                    .contentType(MediaType.APPLICATION_JSON_VALUE)).andExpect(status().isBadRequest());

            verify(userService, never()).login(any());
        }

        @Test
        void when_incorrect_password_then_unauthorized() throws Exception {
            when(userService.login(any())).thenThrow(BadCredentialsException.class);

            mockMvc.perform(post("/users/signin").content(objectMapper.writeValueAsString(DUMMY_USER)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isUnauthorized());

            verify(userService).login(any());
        }

    }

    @Nested
    class Signup {

        @Test
        void ok() throws Exception {
            ArgumentCaptor<UserDto> capture = ArgumentCaptor.forClass(UserDto.class);
            when(userService.save(any())).thenAnswer(a -> a.getArgument(0));

            assertThat(DUMMY_USER).usingRecursiveComparison().isEqualTo(objectMapper.readValue(TestUtils.readResponse.apply(mockMvc
                    .perform(post("/users/signup").content(objectMapper.writeValueAsString(DUMMY_USER)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isCreated())), UserDto.class));

            verify(userService).save(capture.capture());
            assertThat(capture.getValue()).usingRecursiveComparison().isEqualTo(DUMMY_USER);
        }

        @Test
        void when_already_exist_then_conflict() throws Exception {
            when(userService.save(any())).thenThrow(AlreadyExistException.class);

            mockMvc.perform(post("/users/signup").content(objectMapper.writeValueAsString(DUMMY_USER)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isConflict());

            verify(userService).save(any());
        }

        @Test
        void when_invalid_then_bad_request() throws Exception {
            mockMvc.perform(post("/users/signup").content(objectMapper.writeValueAsString(new UserDto("", "", null)))
                    .contentType(MediaType.APPLICATION_JSON_VALUE)).andExpect(status().isBadRequest());

            verify(userService, never()).save(any());
        }

        @Test
        void when_exception_then_internal_server_error() throws Exception {
            when(userService.save(any())).thenThrow(new ArithmeticException());

            mockMvc.perform(post("/users/signup").content(objectMapper.writeValueAsString(DUMMY_USER)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isInternalServerError());

            verify(userService).save(any());
        }

    }

    @Nested
    class UpdatePassword {

        @Test
        @WithMockUser
        void ok() throws Exception {
            ArgumentCaptor<PasswordDto> capture = ArgumentCaptor.forClass(PasswordDto.class);
            doNothing().when(userService).updatePassword(any());

            mockMvc.perform(
                    put("/users/password").content(objectMapper.writeValueAsString(DUMMY_PASSWORD)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isNoContent());

            verify(userService).updatePassword(capture.capture());
            assertThat(capture.getValue()).usingRecursiveComparison().isEqualTo(DUMMY_PASSWORD);
        }

        @WithMockUser
        @ParameterizedTest(name = "Given new password ''{0}'' and old password ''{1}'' when updates password then bad request")
        @CsvSource({ ", password", "o, password", "01234567891011121314151617181920, password", "password,", "password, o",
                "password, 01234567891011121314151617181920", })
        void when_failed_validation_then_bad_request(String newPassword, String oldPassword) throws Exception {
            mockMvc.perform(put("/users/password").content(objectMapper.writeValueAsString(new PasswordDto(oldPassword, newPassword)))
                    .contentType(MediaType.APPLICATION_JSON_VALUE)).andExpect(status().isBadRequest());

            verify(userService, never()).updatePassword(any());
        }

        @Test
        void not_authenticated_then_unauthorized() throws Exception {
            mockMvc.perform(
                    put("/users/password").content(objectMapper.writeValueAsString(DUMMY_PASSWORD)).contentType(MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isUnauthorized());

            verify(userService, never()).updatePassword(any());
        }

    }

}
