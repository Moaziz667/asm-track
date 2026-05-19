import os
os.environ["PATH"] += r";C:\Program Files (x86)\Graphviz\bin"

from diagrams import Diagram, Cluster, Edge
from diagrams.generic.network import Router
from diagrams.onprem.database import Postgresql
from diagrams.generic.database import SQL
from diagrams.generic.compute import Rack
from diagrams.onprem.compute import Server
from diagrams.generic.os import Android
from diagrams.programming.framework import Spring
from diagrams.onprem.client import Client  # web browser clients

graph_attr = {
    "fontsize": "13",
    "bgcolor": "white",
    "pad": "0.6",
    "splines": "ortho",
    "nodesep": "0.5",
    "ranksep": "1.0",
    "fontname": "Arial",
}

with Diagram(
    "",
    filename="docs/img/container-diagram",
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=graph_attr,
):
    with Cluster("Clients"):
        admin = Client("Admin App")
        superadmin = Client("Super Admin")
        driver = Android("Driver App")

    gw = Router("API Gateway\n:80")

    with Cluster("Microservices"):
        with Cluster(""):
            ab = Spring("AppBackend\n:8080")
            pgapp = Postgresql("postgres-app")

        with Cluster(""):
            dl = Spring("DeliveryMicroservice\n:8082")
            pgdl = Postgresql("postgres-delivery")

        with Cluster(""):
            ds = Spring("DriverService\n:8086")
            pgds = Postgresql("postgres-driver")

        with Cluster(""):
            ea = Spring("ErpAdapterService\n:8088")
            h2 = SQL("H2")

    with Cluster("External"):
        odoo = Server("Odoo / DUX")
        minio = Rack("MinIO")
        osrm = Server("OSRM")

    [admin, superadmin, driver] >> gw

    gw >> ab
    gw >> dl
    gw >> ds

    ab - pgapp
    dl - pgdl
    ds - pgds
    ea - h2

    dl >> Edge(label="ERP sync", style="dashed") >> ea
    ea >> odoo
    dl >> Edge(label="S3") >> minio
    dl >> Edge(label="HTTP") >> osrm
