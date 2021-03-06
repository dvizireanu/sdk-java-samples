package com.geotab.sdk.importusers;

import static com.geotab.http.invoker.ServerInvoker.DEFAULT_TIMEOUT;

import com.geotab.api.GeotabApi;
import com.geotab.http.exception.DbUnavailableException;
import com.geotab.http.exception.InvalidUserException;
import com.geotab.http.request.AuthenticatedRequest;
import com.geotab.http.request.param.EntityParameters;
import com.geotab.http.request.param.SearchParameters;
import com.geotab.http.response.GroupListResponse;
import com.geotab.http.response.IdResponse;
import com.geotab.http.response.UserListResponse;
import com.geotab.model.Id;
import com.geotab.model.entity.group.Group;
import com.geotab.model.entity.group.SecurityGroup;
import com.geotab.model.entity.user.User;
import com.geotab.model.enumeration.UserAuthenticationType;
import com.geotab.model.login.Credentials;
import com.geotab.model.login.LoginResult;
import com.geotab.model.search.GroupSearch;
import com.geotab.util.CollectionUtil;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class ImportUsersApp {

  public static void main(String[] args) throws Exception {
    try {
      if (args.length != 5) {
        System.out.println("Command line parameters:");
        System.out
            .println("java -cp 'sdk-java-samples-1.0-SNAPSHOT.jar;./lib/*'"
                + " com.geotab.sdk.importusers.ImportUsersApp"
                + " 'my.geotab.com' 'database' 'user@email.com' 'password' 'inputFileLocation'");
        System.out.println("server             - The server name (Example: my.geotab.com)");
        System.out.println("database           - The database name (Example: G560)");
        System.out.println("username           - The Geotab user name");
        System.out.println("password           - The Geotab password");
        System.out.println("inputFileLocation  - Location of the CSV file to import.");
        System.exit(1);
      }

      // Process command line arguments
      String server = args[0];
      String database = args[1];
      String username = args[2];
      String password = args[3];
      String filePath = args[4];

      Credentials credentials = Credentials.builder()
          .database(database)
          .password(password)
          .userName(username)
          .build();

      // load CSV
      List<UserDetails> userEntries = loadUsersFromCsv(filePath);

      // Create the Geotab API object used to make calls to the server
      // Note: server name should be the generic server as DBs can be moved without notice.
      // For example; use "my.geotab.com" rather than "my3.geotab.com".
      try (GeotabApi api = new GeotabApi(credentials, server, DEFAULT_TIMEOUT)) {

        // Authenticate user
        authenticate(api);

        // Start import
        importUsers(api, userEntries);
      }

    } catch (Exception exception) {
      // Show miscellaneous exceptions
      log.error("Unhandled exception: ", exception);
    } finally {
      log.info("Press Enter to exit...");
      System.in.read();
    }
  }

  /**
   * Loads a csv file and processes rows into a collection of {@link UserDetails}.
   *
   * @param filePath The csv file name
   * @return A collection of {@link UserDetails}.
   */
  private static List<UserDetails> loadUsersFromCsv(String filePath) {
    log.debug("Loading CSV {} ...", filePath);

    LocalDateTime minDate = LocalDateTime.of(1986, 1, 1, 0, 0, 0, 0);
    LocalDateTime maxDate = LocalDateTime.of(2050, 1, 1, 0, 0, 0, 0);

    try (Stream<String> rows = Files.lines(Paths.get(filePath))) {
      return rows
          .filter(row -> StringUtils.isNotEmpty(row) && !row.startsWith("#"))
          .map(row -> {
            String[] columns = row.split(",");
            String userName = columns[0].trim();
            String password = columns[1].trim();
            String organizationNodes = columns[2].trim();
            String securityNodes = columns[3].trim();
            String firstName = columns[4].trim();
            String lastName = columns[5].trim();

            User user = User.userBuilder()
                .name(userName)
                .firstName(firstName)
                .lastName(lastName)
                .password(password)
                .userAuthenticationType(UserAuthenticationType.BASIC_AUTHENTICATION)
                .activeFrom(minDate)
                .activeTo(maxDate)
                .privateUserGroups(new ArrayList<>())
                .timeZoneId("America/Los_Angeles")
                .isDriver(false)
                .isEmailReportEnabled(true)
                .build();

            return UserDetails.builder()
                .user(user)
                .organizationNodeNames(organizationNodes)
                .securityNodeName(securityNodes)
                .build();
          })
          .collect(Collectors.toList());
    } catch (Exception exception) {
      log.error("Failed to load csv file {} : ", filePath, exception);
      System.exit(1);
    }

    return new ArrayList<>();
  }

  private static LoginResult authenticate(GeotabApi api) {
    log.debug("Authenticating ...");

    LoginResult loginResult = null;

    // Authenticate user
    try {
      loginResult = api.authenticate();
      log.info("Successfully Authenticated");
    } catch (InvalidUserException exception) {
      log.error("Invalid user: ", exception);
      System.exit(1);
    } catch (DbUnavailableException exception) {
      log.error("Database unavailable: ", exception);
      System.exit(1);
    } catch (Exception exception) {
      log.error("Failed to authenticate user: ", exception);
      System.exit(1);
    }

    return loginResult;
  }

  private static void importUsers(GeotabApi api, List<UserDetails> userEntries) {
    log.debug("Start importing users ...");

    try {

      List<User> existingUsers = getExistingUsers(api);
      List<Group> existingGroups = getExistingGroups(api);
      List<Group> securityGroups = getSecurityGroups(api);

      for (UserDetails userDetails : userEntries) {
        // Add groups to user
        User user = userDetails.getUser();
        user.setCompanyGroups(
            getOrganizationGroups(userDetails.getOrganizationNodeNames().split("\\|"),
                existingGroups));
        user.setSecurityGroups(
            filterSecurityGroupsByName(userDetails.getSecurityNodeName(), securityGroups));

        if (isUserValid(user, existingUsers)) {
          try {
            // Add the user.
            AuthenticatedRequest<?> request = AuthenticatedRequest.authRequestBuilder()
                .method("Add")
                .params(EntityParameters.entityParamsBuilder()
                    .typeName("User")
                    .entity(user)
                    .build())
                .build();

            Optional<Id> response = api.call(request, IdResponse.class);

            if (response.isPresent()) {
              log.info("User {} added with id {} .", user.getName(), response.get().getId());
              user.setId(new Id(response.get().getId()));
              existingUsers.add(user);
            } else {
              log.warn("User {} not added; no id returned", user.getName());
            }
          } catch (Exception exception) {
            // Catch and display any error that occur when adding the user
            log.error("Failed to import user {}", user.getName(), exception);
          }
        }

      }

      log.info("Users imported.");
    } catch (Exception exception) {
      log.error("Failed to get import users", exception);
      System.exit(1);
    }

  }

  private static List<User> getExistingUsers(GeotabApi api) {
    log.debug("Get existing users ...");
    try {
      AuthenticatedRequest<?> request = AuthenticatedRequest.authRequestBuilder()
          .method("Get")
          .params(SearchParameters.searchParamsBuilder()
              .typeName("User")
              .build())
          .build();

      return (List<User>) api.call(request, UserListResponse.class).orElse(new ArrayList<>());
    } catch (Exception exception) {
      log.error("Failed to get existing users ", exception);
      System.exit(1);
    }

    return new ArrayList<>();
  }

  private static List<Group> getExistingGroups(GeotabApi api) {
    log.debug("Get existing groups ...");
    try {
      AuthenticatedRequest<?> request = AuthenticatedRequest.authRequestBuilder()
          .method("Get")
          .params(SearchParameters.searchParamsBuilder()
              .typeName("Group")
              .build())
          .build();

      return api.call(request, GroupListResponse.class).orElse(new ArrayList<>());
    } catch (Exception exception) {
      log.error("Failed to get existing groups ", exception);
      System.exit(1);
    }

    return new ArrayList<>();
  }

  private static List<Group> getSecurityGroups(GeotabApi api) {
    log.debug("Get security groups ...");
    try {
      AuthenticatedRequest<?> request = AuthenticatedRequest.authRequestBuilder()
          .method("Get")
          .params(SearchParameters.searchParamsBuilder()
              .typeName("Group")
              .search(GroupSearch.builder()
                  .id(SecurityGroup.SECURITY_GROUP_ID)
                  .build())
              .build())
          .build();

      return api.call(request, GroupListResponse.class).orElse(new ArrayList<>());
    } catch (Exception exception) {
      log.error("Failed to get security groups ", exception);
      System.exit(1);
    }

    return new ArrayList<>();
  }

  /**
   * Searches a list of organization groups matching the names provided.
   */
  private static List<Group> getOrganizationGroups(String[] groupNames, List<Group> groups) {
    List<Group> organizationGroups = new ArrayList<>();
    for (String groupName : groupNames) {
      String name = groupName.trim().toLowerCase();
      if ("organization".equals(name) || "entire organization".equals(name)) {
        name = "**Org**";
      }
      for (Group group : groups) {
        if (group.getName().equalsIgnoreCase(name)) {
          organizationGroups.add(group);
          break;
        }
      }
    }
    return organizationGroups;
  }


  /**
   * Searches a list of security groups matching the names provided.
   */
  private static List<Group> filterSecurityGroupsByName(String name, List<Group> securityGroups) {
    List<Group> groups = new ArrayList<>();

    if (StringUtils.isNotEmpty(name)) {
      name = name.trim().toLowerCase();
      if (name.equals("administrator") || name.equals("admin")) {
        name = "**EverythingSecurity**";
      }
      if (name.equals("superviser") || name.equals("supervisor")) {
        name = "**SupervisorSecurity**";
      }
      if (name.equals("view only") || name.equals("viewonly")) {
        name = "**ViewOnlySecurity**";
      }
      if (name.equals("nothing")) {
        name = "**NothingSecurity**";
      }
      for (Group securityGroup : securityGroups) {
        if (securityGroup.getName().equals(name)) {
          groups.add(securityGroup);
          break;
        }
      }
    }

    return groups;
  }

  /**
   * Validate a user has groups assigned and does not exist.
   */
  private static boolean isUserValid(User user, List<User> existingUsers) {
    if (CollectionUtil.isEmpty(user.getCompanyGroups())) {
      log.warn("Invalid user: {}. Must have organization nodes.", user.getName());
      return false;
    }
    if (CollectionUtil.isEmpty(user.getSecurityGroups())) {
      log.warn("Invalid user: {}. Must have security nodes.", user.getName());
      return false;
    }
    boolean userExists = existingUsers.stream()
        .anyMatch(existingUser -> existingUser.getName().equalsIgnoreCase(user.getName()));
    if (userExists) {
      log.warn("Invalid user: {}. Duplicate user.", user.getName());
      return false;
    }
    return true;
  }

}
