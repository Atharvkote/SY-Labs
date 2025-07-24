#include <iostream>
#include <climits>
using namespace std;

class GraphNode
{
public:
    int source, destination, weight;

    GraphNode()
    {
        source = 0;
        destination = 0;
        weight = 0;
    }

    GraphNode(int src, int dest, int w)
    {
        source = src;
        destination = dest;
        weight = w;
    }
};

class Graph
{
public:
    GraphNode graph[50][50];

    Graph()
    {
        for (int i = 0; i < 50; i++)
        {
            for (int j = 0; j < 50; j++)
            {
                graph[i][j].weight = 0;
            }
        }
    }

    void addEdge(int source, int destination, int weight)
    {
        graph[source][destination] = GraphNode(source, destination, weight);
        graph[destination][source] = GraphNode(destination, source, weight); // since undirected
    }

    void printGraph(int vertices)
    {
        cout << "\nGraph Adjacency Matrix:\n";
        for (int i = 0; i < vertices; i++)
        {
            cout << "[ ";
            for (int j = 0; j < vertices; j++)
            {
                cout << graph[i][j].weight << " , ";
            }
            cout << "]" << endl;
        }
    }

    void printPath(int node, int parent[])
    {
        if (node == -1)
            return;
        printPath(parent[node], parent);
        cout << "Marrage Hall : " << node << " -> ";
    }

    void dijkstra(int start, int vertices)
    {
        int distance[vertices];
        bool visited[vertices];
        int parent[vertices];

        for (int i = 0; i < vertices; i++)
        {
            distance[i] = INT_MAX;
            visited[i] = false;
            parent[i] = -1;
        }

        distance[start] = 0;

        for (int i = 0; i < vertices - 1; i++)
        {
            int minIndex = -1;
            int minValue = INT_MAX;

            for (int j = 0; j < vertices; j++)
            {
                if (!visited[j] && distance[j] < minValue)
                {
                    minValue = distance[j];
                    minIndex = j;
                }
            }

            if (minIndex == -1)
                break;

            visited[minIndex] = true;

            for (int k = 0; k < vertices; k++)
            {
                if (graph[minIndex][k].weight != 0 && !visited[k])
                {
                    if (distance[minIndex] + graph[minIndex][k].weight < distance[k])
                    {
                        distance[k] = distance[minIndex] + graph[minIndex][k].weight;
                        parent[k] = minIndex;
                    }
                }
            }
        }

        cout << "\nShortest distances from source Home -  " << start << ":\n";
        for (int i = 0; i < vertices; i++)
        {
            cout << "Marriage Hall :  " << i << " : " << distance[i] << endl;
        }

        cout << "\nPaths:\n";
        for (int i = 0; i < vertices; i++)
        {
            cout << "Path to Marriage Hall :  " << i << " : ";
            if (distance[i] == INT_MAX)
                cout << "No path";
            else if (i == 0)
                cout << "Destination already reached !";
            else
                printPath(i, parent);
            cout << " (end of the path)" << endl;
        }

        int min_path = INT_MAX;
        int min_path_index = -1;
        for (int i = 1; i < vertices; i++)
        {
            if (distance[i] < min_path)
            {
                min_path = distance[i];
                min_path_index = i;
            }
        }

        if (min_path_index != -1)
        {
            cout << "\n\nThe Nearest Marriage Hall From Home is Marriage hall no. "
                 << min_path_index << " Which is " << min_path << " units away" << endl;
        }
        else
        {
            cout << "\n\nNo reachable Marriage Hall found from Home." << endl;
        }
    }
};

int main()
{
    int vertices, edges;
    cout << "Enter Number of Marriage Halls : ";
    cin >> vertices;
    cout << "Enter Number of Routes with them  : ";
    cin >> edges;

    Graph graph;
    for (int i = 0; i < edges; i++)
    {
        int source, destination, weight;
        cout << "Enter Route's starting Marriage Hall no.(For Home press 0) : ";
        cin >> source;
        cout << "Enter Route's destination Marriage Hall no. : ";
        cin >> destination;
        cout << "Enter Distance b/w them : ";
        cin >> weight;

        graph.addEdge(source, destination, weight);
    }

    graph.printGraph(vertices);

    cout << "\nStarting Vertex is Home - 0 : ";

    graph.dijkstra(0, vertices);

    return 0;
}
