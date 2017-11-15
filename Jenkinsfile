#!/usr/bin/env groovy
pipeline {
    agent any
    stages {
        stage("Checkout") {
            steps {
                checkout scm
            }
        }
        stage("Build") {
            steps {
                withMaven(maven: 'M3', mavenLocalRepo: '.repository') {
                    sh "mvn -Prelease -Dmaven.test.skip=true clean deploy"
                }
            }
        }
        stage("Verify") {
            steps {
                withMaven(maven: 'M3', mavenLocalRepo: '.repository') {
                    sh "/bin/rm -rf ~/.m2/repository/io"
                    sh "mvn -Dmaven.install.skip=true integration-test"
                }
            }
        }
    }
}
