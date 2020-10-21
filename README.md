# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows

## Install k3s

`./init-cluster.sh`

## Startup

`./startup.sh`

## Destroy

`./destroy.sh`

## Login

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - scmadmin / scmadmin.
Change in jenkins-credentials.yaml if necessary.

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with scmadmin/scmadmin
