package colinh.controller

import colinh.model.Article

class Articles extends Application {
  def index {
    at("title", "Colinh Home")
    at("articles", List(new Article, new Article))
    renderView
  }

  def show {

  }

  def edit {

  }
}
