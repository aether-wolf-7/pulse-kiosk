from django.urls import path

from . import views

urlpatterns = [
    path("health/", views.HealthView.as_view()),
    path("machine/config/", views.MachineConfigView.as_view()),
    path("auth/login/", views.LoginView.as_view()),
    path("auth/logout/", views.LogoutView.as_view()),
    path("hevy/link/", views.HevyLinkView.as_view()),
    path("workouts/", views.WorkoutSubmitView.as_view()),
]
