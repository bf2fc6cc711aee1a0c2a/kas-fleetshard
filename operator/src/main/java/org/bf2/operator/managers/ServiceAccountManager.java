package org.bf2.operator.managers;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;

import javax.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ServiceAccountManager {

    /**
     * Get a specific service account information from the ManagedKafka instance
     *
     * @param managedKafka ManagedKafka instance to look at for service accounts
     * @param name name/type of service account to look for
     * @return service account related information
     */
    public Optional<ServiceAccount> getServiceAccount(ManagedKafka managedKafka, ServiceAccount.ServiceAccountName name) {
        List<ServiceAccount> serviceAccounts = managedKafka.getSpec().getServiceAccounts();
        if (serviceAccounts != null && !serviceAccounts.isEmpty()) {
            Optional<ServiceAccount> serviceAccount =
                    serviceAccounts.stream()
                            .filter(sa -> name.toValue().equals(sa.getName()))
                            .findFirst();
            return serviceAccount;
        }
        return Optional.empty();
    }
}
