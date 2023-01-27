package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class Vault extends Feature {
    static final String VAULT_START_SCRIPT_PATH = '/system/secrets/vault/dev-post-start.sh'


    private Map config
    private FileSystemUtils fileSystemUtils
    private HelmClient helmClient
    private File tmpHelmValues
    private K8sClient k8sClient

    Vault(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(),
          K8sClient k8sClient = new K8sClient(), HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.helmClient = helmClient
        this.k8sClient = k8sClient

        tmpHelmValues = File.createTempFile('gitops-playground-control-app', '')
        tmpHelmValues.deleteOnExit()
    }

    @Override
    boolean isEnabled() {
        return config.features['secrets']['active']
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD

        Map yaml = [
                ui: [
                        enabled: true,
                        serviceType: "LoadBalancer",
                        externalPort: 80
                ],
                injector: [
                        enabled: false
                ]
        ]

        if (!config.application['remote']) {
            log.debug("Setting Vault service.type to NodePort since it is not running in a remote cluster")
            yaml['ui']['serviceType'] = 'NodePort'
            yaml['ui']['serviceNodePort'] = 8200
        }

        String vaultMode = config['features']['secrets']['vault']['mode']
        if (vaultMode == 'dev') {
            log.debug("WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n" +
                    "and starts unsealed with a single unseal key. ")
            
            // Create config map from init script
            // Init script creates/authorizes secrets, users, service accounts, etc.
            def vaultPostStartConfigMap = 'vault-dev-post-start'
            def vaultPostStartVolume = 'dev-post-start'
            
            k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, 'secrets', fileSystemUtils.getRootDir() 
                    + VAULT_START_SCRIPT_PATH )

            MapUtils.deepMerge(
                    [
                            server: [
                                    dev: [
                                            enabled: true,
                                            // Don't create fixed devRootToken token (more secure when remote cluster) 
                                            // -> Root token can be found on the log if needed
                                            devRootToken: UUID.randomUUID()
                                    ],
                                    // Mount init script via config-map 
                                    volumes: [
                                            [
                                                    name: vaultPostStartVolume,
                                                    configMap: [
                                                            name: vaultPostStartConfigMap,
                                                            // Make executable
                                                            defaultMode: 0774
                                                    ]
                                            ]
                                    ],
                                    volumeMounts: [
                                            [
                                                    mountPath: '/var/opt/scripts',
                                                    name: vaultPostStartVolume,
                                                    readOnly: true
                                            ]
                                    ],
                                    // Execute init script as post start hook
                                    postStart: [
                                            '/bin/sh',
                                            '-c',
                                                "USERNAME=${config['application']['username']} " +
                                                "PASSWORD=${config['application']['password']} " +
                                                "ARGOCD=${config.features['argocd']['active']} " +
                                                    // Write script output to file for easier debugging
                                                    '/var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log'
                                    ],
                            ]
                    ], yaml)
        }

        log.trace("Helm yaml to be applied: ${yaml}")
        fileSystemUtils.writeYaml(yaml, tmpHelmValues)

        def helmConfig = config['features']['secrets']['vault']['helm']
        helmClient.addRepo(getClass().simpleName, helmConfig['repoURL'] as String)
        helmClient.upgrade('vault', "${getClass().simpleName}/${helmConfig['chart']}",
                helmConfig['version'] as String,
                [namespace: 'secrets',
                 values   : "${tmpHelmValues.toString()}"])
    }
}